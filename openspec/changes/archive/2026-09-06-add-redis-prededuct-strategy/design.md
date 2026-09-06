## Context

Phase 1 的最後一支,也是與前三層**性質不同**的一支。

前三層都是「在 DB 裡想辦法把競爭處理好」——無鎖(壞掉的對照組)、悲觀鎖(排隊)、樂觀鎖(偵測衝突後重做)。三者的共同點是:**回應 201 的那一刻,訂單已經在資料庫裡。** 本層不是。

`openspec/project/backend-architecture.md` 已經替本層定好形狀,本支不重新決定,只執行:

- port 為 `StockCachePort`,**不直接碰 DB 庫存**
- service 為 `RedisPreDeductPurchaseService`,**不標 `@Transactional`** ——
  第 0/1/2 層需要交易,第 3 層不需要(Redis 不參與 DB 交易)
- 策略置於 in port 而非 out port 的論據也已寫定:**第 3 層改變的是「流程與回應時機」,不是扣庫存的手法**。
  前三層是「扣庫存 → 建訂單」的同步順序,本層是「扣 Redis → 回應 → 非同步落庫」
- 已明確否決 template method:骨架本質不同,以繼承綁定會讓骨架充斥 hook 與條件分支

探索既有程式碼發現的其他事實:

- **Redis 在本 repo 目前完全不存在**:`pom.xml` 無相依、`compose.yml` 無服務、
  `TestcontainersConfiguration` 只有 postgres、`adapter/out/redis/` 只有一個 `package-info.java`
- 但那個 `package-info` 已經寫下本層的兩個要求:「**扣減以 Lua 腳本保證原子性**;
  **扣減成功但落庫失敗需有補償機制**」
- `backend-runtime.md` 連 compose 的 `redis-perf` 片段都寫好了(`image: redis:7` + `redis-cli ping` healthcheck)
- `purchase_order.idempotency_key` 的唯一約束在第 2 支就建好,當時的註解寫明
  「在有重試機制的系統中這是必要條件而非加分項:**Redis 預扣的補償機制也會重試**」——
  **本支是它第一次真正派上用場**
- `TestcontainersConfiguration` 的容器以 `private static final` 欄位持有,
  新增 Redis 容器必須沿用同一寫法(第 2 支的 lesson:放進 `@Bean` 方法會被每個 context 各起一個)

## Goals / Non-Goals

**Goals:**

- Redis 原子預扣(Lua),零超賣
- 非同步落庫:Redis Stream + consumer group
- 即時補償 + 週期對帳,差額收斂到 0
- 注入落庫失敗,證明補償確實有效
- 限購在 Redis 端同樣不被突破
- 與第 1 層(悲觀鎖)在相同條件下的效能對照,並補上四層總表

**Non-Goals:**

- **不修改第 0、1、2 層** —— 四層必須並存才能比較
- **不做 Redis 高可用 / Sentinel / Cluster** —— 單節點足以呈現本層的併發特性,
  高可用是部署議題(Phase 4),混進來會讓本層的數據多出一個無關的變數
- **不做訂單狀態機與逾時釋放** —— Phase 2
- **不把 Redis 當最終帳本** —— 見 D1
- **不追求本層的效能最佳化**

## Decisions

### D1. Redis 是准入控制,DB 是最終帳本。兩者不對等

**這是本層其他所有決定的前提,先講清楚。**

- **Redis 決定「誰可以買」** —— 它是門口的閘門,快、原子、但可能與帳本短暫不一致
- **DB 記錄「誰買到了」** —— 它是唯一的真相來源

因此**超賣的定義完全不變**:DB 的訂單張數總和是否超過初始庫存。
Redis 扣了但還沒落庫的那些 —— **不是超賣,是待收斂的差額**。

這讓本層的驗收比前三層多一個維度:

| 驗收 | 對象 | 意義 |
| --- | --- | --- |
| 零超賣 | DB | 與前三層相同的標準,不放寬 |
| 對帳收斂 | Redis 與 DB 的差額 | 本層特有:最終一致要真的「最終」會一致 |

**不選「Redis 就是帳本、DB 只是備份」**:那會讓「超賣」的定義隨策略而變,四層就無法用同一把尺量。
一個為了比較而存在的專案,不能讓判準跟著被比較的對象一起變。

### D2. 預扣以單一 Lua 腳本完成,**庫存與限購都在腳本內**

```lua
-- KEYS[1] = stock:{eventId}      KEYS[2] = purchased:{eventId}:{userId}
-- ARGV[1] = quantity             ARGV[2] = limit
local stock = redis.call('GET', KEYS[1])
if not stock then return -3 end                      -- 場次未開賣
local purchased = tonumber(redis.call('GET', KEYS[2]) or '0')
if purchased + tonumber(ARGV[1]) > tonumber(ARGV[2]) then return -1 end   -- 超過限購
if tonumber(stock) < tonumber(ARGV[1]) then return -2 end                 -- 庫存不足
redis.call('DECRBY', KEYS[1], ARGV[1])
redis.call('INCRBY', KEYS[2], ARGV[1])
return 1
```

**限購為什麼一定要進腳本:** 若限購檢查留在應用端(讀 Redis → 判斷 → 扣),那就是
**check-then-act** —— 兩個併發請求讀到相同的已購數、各自通過檢查。
**那正是第 0 層的缺陷,只是換了一個儲存體重演一次。** Redis 的 Lua 是單執行緒原子執行的,
庫存與限購寫在同一支腳本內才都受保護。

**回傳碼區分三種失敗**,與第 2 層 D2 同樣的理由:合併成一個「失敗」會讓呼叫端無法決定處置
(換場次 / 放棄 / 稍後再來是三種不同的行為)。

**不選「先 `DECRBY`,發現變負再回補」**:高併發下庫存會短暫為負,而對帳任務可能正好在那一刻讀到負值並誤判;
而且回補本身也需要原子性,等於把問題往後推一層。

**不選 `WATCH` / `MULTI` / `EXEC`**:那是 Redis 版的樂觀鎖,高競爭下會重演第 7 支量到的重試風暴 ——
**而我們已經知道那個代價了(每賣一張票伴隨約 13 次失敗嘗試)**。Lua 是無競爭的原子執行,不需要重試。

**缺 key 回傳「場次未開賣」而不是當作庫存無限**:缺 key 必須是保守的失敗。
把「不知道」當成「可以」是超賣最廉價的來源。

### D3. 非同步落庫走 Redis Stream + consumer group,順序是「落庫 → 成功則 ack」

```
預扣成功 → XADD orders * ...           → 回應客戶端
consumer: XREADGROUP → 落庫 → 成功 → XACK
                            → 失敗 → INCRBY 回補庫存與已購數 → XACK
                            → 崩潰 → 不 ack,訊息留在 pending
```

**不選 Redis List + BLPOP**:`BLPOP` 取出即離開佇列,pop 之後、落庫之前崩潰就掉單,**且不留痕跡**。
而「掉單能不能補回來」正是本層要驗的東西 —— 用一個會無聲掉單的載體,
會分不清失敗是設計造成的還是載體造成的。

**不選應用內佇列(`@Async` / `BlockingQueue`)**:佇列在 JVM heap 裡,一重啟全掉;
而且**佇列長度是一個隱形的背壓來源**,會讓壓測的 req/s 被高估(請求看起來很快,只是工作被推遲了)。
本專案的產出是跨層的吞吐比較,不能有一層是靠「還沒做完的工作」贏的。

### D4. 重複落庫靠冪等鍵擋下 —— 這是「回補後、ack 前崩潰」唯一的解

D3 的流程有一個窗口:**回補成功、XACK 之前崩潰**。訊息會留在 pending 被重新領取,
於是同一筆預扣被落庫兩次,而庫存已經回補過 —— **那是真的超賣。**

解法不是把窗口縮小(縮不掉),而是讓重複落庫無害:

```
落庫時撞上 uq_purchase_order_idempotency_key
  → 代表這筆先前已經成功落庫過
  → 視為成功,直接 XACK,**不回補**
```

`GlobalExceptionHandler` 已經有辨識該約束名稱的邏輯(第 3 支建立),consumer 沿用同一個判斷。

**這是冪等鍵在本專案第一次真正被使用。** 前三支只是把它存下來並擋重複請求;
本支開始它承擔正確性。第 2 支寫下「在有重試機制的系統中這是必要條件而非加分項」時就預期了這件事。

### D5. 週期對帳:差額 =(初始庫存 − Redis 餘量)− DB 訂單張數,**且只在 pending 為空時回補**

即時補償只處理「我知道我失敗了」的情況。崩潰、逾時、consumer 被殺 —— 這些沒有人回補,
只有對帳抓得到。因此兩者都要,不是二選一。

**`pending 為空` 這個條件是本決定的核心。**

pending 裡的訊息代表「還在飛」——它們造成的差額是**暫時且正常的**。
若不看 pending 就回補,會把還在處理中的訂單所佔的庫存還回去,而那些訂單稍後落庫成功
→ **真的超賣。**

> **對帳真正的危險不是漏補,是誤補。**
> 漏補的代價是少賣幾張票;誤補的代價是超賣 —— 而超賣正是整個專案要消滅的東西。
> 兩者不對等,因此對帳一律偏保守。

**不選「以 DB 為準,直接把 Redis 覆寫成 `初始庫存 − DB 訂單數`」**:那個寫法會把
「還在飛的訂單」佔用的庫存也放出去,是誤補的最極端形式。而且它是覆寫而非增量,
與併發的預扣互相踩踏。

### D6. 回應改為 **202 Accepted**,且**不提供 `orderId`**

**本支唯一改動 API 契約的地方,請特別看這一條。**

前三層回 201 時訂單已在 DB,`orderId` 是資料庫產生的。本層回應的那一刻訂單**還不存在** ——
它只存在於 Redis Stream 裡的一則訊息。

| 選項 | 問題 |
| --- | --- |
| 沿用 201,`orderId` 給 null | **謊報**。201 Created 的語意是資源已建立,而它還沒有 |
| 改主鍵為應用端產生,保住 201 | 要改 migration 與 `purchase_order.id` 的產生方式,**會動到前三層** —— 四層就不再並存 |
| **202 Accepted,回 `idempotencyKey` 與 `PENDING`** | **採用** |

202 的語意正是「已受理、尚未完成」,精確描述了本層發生的事。

**這個差異是第 3 層的本質,不是缺陷,不得為了讓四層看起來一致而假裝訂單已建立。**
`backend-architecture.md` 自己就說本層「改變的是流程與**回應時機**」——202 是那句話的必然結論。
回應仍使用既有的統一 wrapper,只是 `data` 內是 `idempotencyKey` 與 `status`,`orderId` 為 null。

**代價要說清楚:** 客戶端要區分四層的回應。但那個代價是誠實的 ——
一個「回 201 但訂單可能三秒後才出現」的 API,會讓客戶端寫出更糟的錯誤處理。

### D7. Redis 庫存由壓測腳本以 `redis-cli` 初始化,應用不提供載入端點

Redis 的 `stock:{eventId}` 從哪來?

**不選 lazy load(第一次購買時從 DB 載入)**:那個載入本身有競爭,要再設計一次同步機制;
而且它會讓「key 不存在」與「庫存為 0」混淆 —— 而 D2 剛剛才決定前者必須是保守的失敗。

**不選在應用開一個「載入庫存」端點**:`k6/run-load-test.sh` 的既有註解已經定調 ——
「刻意不為此在應用加測試專用端點,那會污染正式 API」。

**採用:`run-load-test.sh` 重置場次後,直接以 `redis-cli SET` 寫入。**
與它現在用 `psql` 重置 DB 完全對稱,不需要應用配合。
Phase 2 的管理介面才是「開賣」這個動作的正確歸屬。

### D8. 落庫失敗以測試替身注入,production 不留開關

驗收要求「注入落庫失敗後補償有效」。

**不選在 production 加一個「故障注入」設定**:那是為了測試而汙染 production 的典型做法,
而且一個能讓落庫失敗的開關,本身就是一個風險。

**採用:整合測試以 spy 讓 `SaveOrderPort` 拋例外**,驗證補償路徑。
注入失敗是**正確性驗收**,不是效能驗收 —— 壓測不需要它。

### D9. consumer 與對帳排程置於 `adapter/in/scheduler`,經 in port 進入 application

consumer 是**入站** adapter(訊息驅動),不是出站。它不得直接呼叫 out port ——
那會讓 adapter 跨過 application 直接碰持久化,違反既有的分層守則。

```
adapter/in/scheduler/OrderPersistenceConsumer  →  PersistPendingOrderUseCase (in port)
adapter/in/scheduler/ReconciliationJob         →  ReconcileStockUseCase      (in port)
                                                            ↓
                                                  application/service/...
                                                            ↓
                                                        out ports
```

**新增 in port 而非重用 `PurchaseTicketUseCase`**:落庫與對帳不是「購票」,
把它們塞進同一個 in port 會讓 `PurchaseFacade` 的 `Map<String, PurchaseTicketUseCase>`
混入不是策略的東西,而那個 Map 正是「四種實作」的載體。

## Risks / Trade-offs

- **[本支的實作量明顯大於前七支]** → 切成 8 塊,每塊獨立通過驗證鏈。
  這是誠實的結果:本層要新增 Redis 相依、compose 服務、測試容器、Lua 腳本、
  Stream 生產與消費、補償、對帳、以及一個新的回應語意
- **[對帳的誤補會造成超賣]** → D5 的 `pending 為空` 條件是主要防線,
  且必須**以測試證明**:在 pending 非空時對帳不得回補
- **[最終一致是本層的定義性代價]** → 不隱藏。壓測要記錄「回應後到訂單可見的延遲」,
  那是本層真正賣掉的東西
- **[本層的吞吐可能是四層最好的]** → 若如此,**必須同時說明它拿什麼換的**:
  回應時訂單不存在、需要對帳、多一個必須運維的元件。
  **只報吞吐不報代價,是本專案最該避免的那種數據**
- **[Lua 腳本沒有型別檢查也沒有測試框架]** → 以整合測試對真實 Redis 驗證,
  並針對三種回傳碼各寫一個案例。反向驗證:拿掉限購那段,超買必須回來

## Migration Plan

**無 schema 變更。** `purchase_order.idempotency_key` 的唯一約束在第 2 支已建立,本支只是開始依賴它。

**新增相依:** `spring-boot-starter-data-redis`。

新增策略不影響既有的第 0、1、2 層。既有檔案的改動集中在:
`pom.xml`、`compose.yml`、`TestcontainersConfiguration`、`ErrorCode`、
`GlobalExceptionHandler`、`k6/run-load-test.sh`、`application.yml`。

## Open Questions

- **對帳的執行間隔** —— 暫定 1 秒且可設定。壓測時可能需要調整:間隔太長,
  收斂在壓測結束前看不到;太短則對帳本身成為負載。**取得第一組數據後再定案**,
  屆時把實際值與理由寫回本節。
