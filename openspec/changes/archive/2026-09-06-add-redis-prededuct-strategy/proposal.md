## Why

前三層都在回答同一個問題:「**在資料庫裡**要怎麼處理競爭。」無鎖壞掉、悲觀鎖排隊、樂觀鎖重做。
第 7 支已經量出樂觀鎖在高競爭下的代價(吞吐比悲觀鎖低 28.4%,每賣一張票伴隨約 13 次失敗嘗試)。

本層改問另一個問題:「**如果根本不讓競爭進到資料庫呢?**」

把庫存搬到 Redis 當閘門,請求在記憶體裡就決定成敗,訂單非同步落庫。
這是四層裡唯一**改變流程而非改變鎖法**的一層,也是唯一一個 **回應時訂單還不存在** 的一層。

代價因此也換了種類:前三層付的是吞吐與延遲,本層付的是**一致性與運維複雜度** ——
多一個必須維護的元件、一段最終一致的窗口、以及一套必須真的會收斂的對帳機制。

**Phase 1 的收尾要求本支同時交出四層總表**,而總表的價值在於每一層都附上它換掉了什麼。

**怎樣算做完:**

1. **零超賣**:1000 併發搶 500 張,DB 的訂單張數總和恰好 500(**判準與前三層完全相同,不放寬**)
2. **零超買**:同一人併發下單,成交張數恰好等於限購上限
3. **對帳收斂**:注入落庫失敗後,(初始庫存 − Redis 餘量)− DB 訂單張數 最終回到 0
4. **誤補不發生**:pending 非空時對帳不得回補 —— 這條比第 3 條更重要
5. 效能數據與第 1 層在**相同測量條件**下對照,並補上四層總表

## What Changes

- 新增相依 `spring-boot-starter-data-redis`;`RedisConfig` 置於 `infrastructure/config/`
- `compose.yml` 新增 `redis-perf` 服務;`TestcontainersConfiguration` 新增 `redis:7` 容器
  (沿用既有的 `private static final` 寫法)
- 新增 out port `StockCachePort`:以**單一 Lua 腳本**原子完成「限購檢查 + 庫存檢查 + 扣減」,
  回傳碼區分三種失敗
- 新增 out port `OrderStreamPort`:預扣成功後 `XADD` 到 Redis Stream
- 新增 `RedisPreDeductPurchaseService`(bean name `redisPreDeduct`,**不標 `@Transactional`**)
- 新增兩個 in port 與其 service:`PersistPendingOrderUseCase`(落庫)、`ReconcileStockUseCase`(對帳)
- 新增兩個入站 adapter:`OrderPersistenceConsumer`(consumer group)、`ReconciliationJob`(排程)
- 新增即時補償(落庫失敗 → 回補 Redis)與週期對帳(**只在 pending 為空時回補**)
- **購票回應改為 202 Accepted,不提供 `orderId`** —— 見 design D6
- 新增錯誤碼 `EVENT_NOT_ON_SALE`(Redis 無該場次庫存)
- 新增併發正確性驗收測試與對帳收斂測試
- 取得本層壓測數據,並整理**四層總表**

## Capabilities

### New Capabilities

- `strategy-redis-prededuct`:第 3 層的驗收 —— 正確性(零超賣、零超買、對帳收斂、**誤補不發生**)、
  效能量測項目(含「回應後到訂單可見」的延遲與對帳差額)、與第 1 層的對照,
  以及本層特有的兩條約束:Lua 內必須同時涵蓋限購與庫存、對帳只在 pending 為空時回補。

### Modified Capabilities

- `api-ticket-purchase`:第 3 層回 **202 Accepted** 且不提供 `orderId`。
  **這是刻意的語意差異,不是實作缺陷** —— 訂單在回應當下確實還不存在。
- `platform-api-response-format`:新增錯誤碼 `EVENT_NOT_ON_SALE`。
- `platform-load-test-environment`:新增 Redis 服務與**對帳間隔**作為測量條件;
  新增「回應後到訂單可見的延遲」為本層特有的量測項目。

## Impact

**新增相依:** `spring-boot-starter-data-redis`。**不引入 Redisson** ——
本層需要的只有 Lua 腳本與 Stream,`StringRedisTemplate` 足夠;Redisson 會帶進一整套
分散式鎖與物件,而分散式鎖正是本層刻意不用的東西(那會變成第 1 層的遠端版)。

**受影響的既有檔案:**

| 檔案 | 變更 |
| --- | --- |
| `pom.xml` | 新增 Redis starter |
| `compose.yml` | 新增 `redis-perf` 服務與 app 的 `depends_on` |
| `TestcontainersConfiguration` | 新增 `redis:7` 容器 |
| `ErrorCode` | 新增 `EVENT_NOT_ON_SALE` |
| `GlobalExceptionHandler` | 新增一個 handler |
| `PurchaseController` | 依策略回 201 或 202 |
| `application.yml` | Redis 連線、對帳間隔、Stream 設定 |
| `k6/run-load-test.sh` | 以 `redis-cli` 初始化庫存;壓測後印對帳差額 |

**不受影響:** 第 0、1、2 層的實作**完全不動**。四層必須能在同一個建置中並存才能互相比較。

**無 schema 變更。** `uq_purchase_order_idempotency_key` 在第 2 支已建立 ——
本支是它第一次真正承擔正確性(擋下 consumer 的重複落庫)。

**需要使用者手動執行:** 壓測(方式與第 4、6、7 支相同,新增 Redis 服務與對帳間隔兩個條件)。
