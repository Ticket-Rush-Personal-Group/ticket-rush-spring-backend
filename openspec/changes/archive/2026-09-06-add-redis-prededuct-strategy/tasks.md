> 每一塊(`##` 標題)須能獨立通過驗證鏈:
> `./mvnw compile && ./mvnw spotless:check && ./mvnw verify`
> **本支引入新的外部相依與新的回應語意,`verify` 是必要的。既有 110 個測試必須全程維持綠。**
> **本層的併發測試是驗收測試,必須進入預設 `verify`** —— 與第 1、2 層相同,不以 tag 隔離。
> 每塊綠燈才進下一塊。**不逐塊 commit** —— 一支 change 一個 commit,收尾塊(塊 8)才給指令。
>
> **本支是 Phase 1 最大的一支**,切成 8 塊。那不是切太細,是本層真的要新增:
> Redis 相依與容器、Lua 原子扣減、Stream 生產與消費、即時補償、週期對帳、以及一個新的回應語意。
>
> 塊的依賴關係(一條線,無法平行):
> - 塊 1(基礎設施)先做 —— 沒有 Redis 容器,後面每一塊的測試都跑不起來。
> - 塊 2(Lua 預扣)→ 塊 3(策略與 API)→ 塊 4(consumer)→ 塊 5(補償與對帳)為一條鏈。
> - 塊 6(併發驗收)依賴塊 5;塊 7(壓測)依賴塊 6。

## 1. Redis 基礎設施

- [x] 1.1 `pom.xml` 新增 `spring-boot-starter-data-redis`(與 `-test`)。**未引入 Redisson**
- [x] 1.1b **踩到的座標問題:Testcontainers 的 redis 模組 groupId 是 `com.redis` 而非 `org.testcontainers`。**
      Testcontainers 官方 BOM(2.0.5)沒有 redis 模組,該模組由第三方提供,但版本仍由
      Spring Boot 的 BOM 管理,故不寫死版本。寫錯 groupId 的症狀是
      「`dependencies.dependency.version` is missing」而**不是**「找不到 artifact」——
      錯誤訊息指向版本,實際原因是 groupId
- [x] 1.2 ~~`RedisConfig` 於本塊建立~~ → **移至塊 2**。
      `StringRedisTemplate` 與連線工廠由 Spring Boot 自動組態,本塊不需要任何自訂 bean;
      唯一要放進 `RedisConfig` 的是 Lua 腳本的 `RedisScript` bean,而腳本本身屬於塊 2。
      **在本塊建立一個空的組態類別是為了對齊任務清單而寫程式碼**,不是需求
- [x] 1.3 `application.yml` 新增 `spring.data.redis`(開發環境連共用的 `~/dev-databases`)。
      Stream / consumer group / 對帳間隔等設定**留到讀取它們的程式碼落地時再加** —— 同 1.2 的理由
- [x] 1.4 `compose.yml` 新增 `redis-perf`(`redis:7`、2 核 / 1GB、`redis-cli ping` healthcheck、
      不對外開埠),app 加上 `depends_on` 與 `SPRING_DATA_REDIS_HOST/PORT`。
      **關閉 RDB/AOF**(`--save "" --appendonly no`):落盤時機會在測量期間插入
      與併發行為無關的暫停。資源限制與 postgres 對齊 —— **第 0/1/2 層完全不碰這個服務,
      它的存在本身就是第 3 層的代價**
- [x] 1.5 `TestcontainersConfiguration` 新增 `RedisContainer("redis:7")`,
      **沿用既有的 `private static final` 欄位寫法**。
      **實測容器啟動次數:postgres:17 × 1、redis:7 × 1**(另有 Testcontainers 自身的 ryuk,
      不屬專案容器)—— static 欄位的寫法在新增第二個容器後依然成立
- [x] 1.5b 新增 `RedisConnectivityTest`,其中一條斷言**驗證連的是測試容器而非共用的 `~/dev-databases`**。
      這條不是形式:`@ServiceConnection` 若失效,測試會連到 `application.yml` 指定的
      `localhost:6379`,而開發者本機那台 Redis 真的在跑 —— **測試依然會全綠**,
      但實際上違反了「併發測試不得跑在共用資料庫」的守則,且 CI 上會變成「本機過、CI 掛」。
      容器映射隨機宿主埠,因此「埠不等於 6379」就是覆蓋生效的證據
- [x] 1.6 **驗證**:`./mvnw verify` 綠,112 個測試

## 2. StockCachePort 與 Lua 原子預扣

- [x] 2.1 `StockCachePort` 與 `PreDeductResult`(列舉,四種結果)。
      **回傳碼只在 adapter 內被翻譯** —— 讓 `-2` 出現在 application service 裡,
      會使「它是什麼意思」變成跨層才答得出來的問題
- [x] 2.2 `resources/redis/pre-deduct.lua`:**限購檢查與庫存檢查在同一支腳本內**。
      檢查順序為「先限購、後庫存」,與前三層一致 —— 四層順序不同的話,
      同一組輸入在不同層會得到不同的錯誤碼
- [x] 2.3 庫存 key 不存在 → 回 `NOT_ON_SALE`,且**不留下任何痕跡**(不建立已購數)
- [x] 2.4 `StockCacheAdapter` 實作該 port;新增 `RedisKeys` 集中 key 命名 ——
      預扣、對帳、測試、壓測腳本四處都要拼出相同的 key,分散拼接的漂移症狀是
      「對帳永遠算出差額」,看起來像邏輯錯誤,實際上只是兩邊在讀不同的 key
- [x] 2.4b **踩到的編譯問題:Java 的 `switch` 不接受 `long` selector。**
      腳本回傳 `Long`,比對前需縮為 `int`
- [x] 2.5 `StockCacheBehaviourTest`:四種回傳結果各一案例,**並驗證失敗時兩個 key 都不變動** ——
      只檢查回傳碼的測試,對「拒絕了但庫存已扣」沒有任何防護力,
      而那是預扣策略最糟的失效方式:票沒賣出去,庫存卻消失了
- [x] 2.6 併發原子性:200 執行緒搶 100 張(不同使用者)→ 成功**恰好 100**、餘量**恰好 0**
- [x] 2.6b **追加:同一人 200 併發 → 成功恰好等於限購上限。**
      原 task 只安排了不同使用者的併發測試,那驗不到限購的原子性 ——
      而限購在腳本內正是 D2 的核心主張,必須有測試直接守住
- [x] 2.7 **反向驗證兩項,全部成立**:

      **(1) 拿掉 `DECRBY` 前的庫存檢查** → 100 張庫存被賣出 **200 次**,超賣 100 張

      **(2) 把限購移出腳本、改為應用端 check-then-act** → 上限 4,而 **200 個併發全部成功**。

      **第二項的結果比 DB 層那次極端得多:第 5 支在資料庫層做同樣的事只超買 6 張,
      這裡是完全失效。** 成因是 Redis 快到 200 個讀取全部發生在任何一次寫入落地之前 ——
      **儲存體越快,check-then-act 的窗口就越接近「全部同時」**,缺陷不是變小而是變大。
      這正是 D2 堅持限購必須留在腳本內的實測依據

      兩項還原後 `git status` 皆乾淨
- [x] 2.8 **驗證**:`./mvnw verify` 綠,119 個測試

## 3. RedisPreDeductPurchaseService 與 202 回應

- [x] 3.1 `OrderStreamPort` 與 `OrderStreamAdapter`(`XADD`,欄位以 Stream 原生的 field-value map
      儲存,不再包一層 JSON)。**與 `StockCachePort` 分開** —— 兩者的失敗處置相反:
      預扣失敗代表「不能買」,投遞失敗代表「買到了但沒人會處理」,後者必須回補
- [x] 3.2 `RedisPreDeductPurchaseService`,bean name `redisPreDeduct`,**未標 `@Transactional`**
- [x] 3.3 `XADD` 失敗 → 回補預扣後再向上拋。
      新增 `restore.lua`:**庫存加回與已購數減回必須原子完成**。分兩次呼叫的話,
      中間有一段「庫存已加回、已購數還沒減」的時間 —— 該使用者的限購額度在那個瞬間
      被錯誤地佔用著,而他可能正好在重試。這種不一致不會報錯,
      只會讓某些使用者莫名其妙買不到票
- [x] 3.3b **`StockCachePort` 追加 `available()` 與 `purchasedBy()`。**
      原因是既有的領域例外需要具體數字才能組出誠實的訊息
      (`InsufficientStockException` 要餘量、`PurchaseLimitExceededException` 要已購數),
      而 Lua 腳本只回傳結果碼。**這兩個方法的 javadoc 明寫「僅供錯誤訊息,不得作為判斷依據」** ——
      判斷已在腳本內原子完成,此處讀到的是事後快照。
      `available()` 在塊 5 的對帳會再次用到,不是為了訊息才存在的方法
- [x] 3.4 `ErrorCode` 新增 `EVENT_NOT_ON_SALE`;新增 `application/exception/EventNotOnSaleException`;
      handler → **409 而非 404**:場次**存在**,只是還沒開賣,404 會讓使用者以為連結有誤
- [x] 3.5 **回應改為 202 Accepted。**
      **controller 依 `result.orderId() == null` 判斷,不問策略身分** —— 它據以分支的是
      「訂單建立了沒」這個事實,而那正是回應語意的真正來源。若改成詢問策略名稱再分支,
      策略就洩漏到 adapter 層了
- [x] 3.6 `PurchaseResponse` 改為 `created()` / `accepted()` 兩個工廠,並加上
      `@JsonInclude(NON_NULL)`。**規則是「回應永遠帶著一個能指認這筆訂單的東西」** ——
      已建立回 `orderId`,已受理回 `idempotencyKey`。
      **同步策略的回應與本支之前完全相同**(不會多出 `"idempotencyKey": null`)
- [x] 3.6b **與 spec 範例的差異:實作為「欄位不出現」而非 `"orderId": null`。**
      理由取自 `ApiResponse` 既有的處置 ——「沒有值的欄位不出現,比出現一個 null 更接近事實」。
      **spec 的 202 範例需於收尾時同步更正**,否則文件與實作不符
- [x] 3.7 `RedisPreDeductContractTest`(5 個案例):202 且無 `orderId`、回冪等鍵、
      不含策略識別資訊、未開賣 → `EVENT_NOT_ON_SALE`、超限、庫存不足。
      **既有的 201 契約測試全部維持綠** —— 那就是「同步策略形狀未變」的證據
- [x] 3.8 **驗證**:`./mvnw verify` 綠,124 個測試

## 4. Stream consumer 與落庫

- [x] 4.1 `PersistPendingOrderUseCase`(in port)+ `PersistOutcome`(PERSISTED / DUPLICATE)
      + `PersistPendingOrderService`(`@Transactional`)。
      **結果用列舉而非布林**:只回「成功與否」的話,「重複」很容易被歸類成失敗,
      而失敗會回補庫存 —— 那筆庫存其實已經賣出去了,回補它就是超賣
- [x] 4.2 `adapter/in/scheduler/OrderPersistenceConsumer`,以 consumer group 消費,只呼叫 in port
- [x] 4.2b **踩到的關閉順序問題:改用 `SmartLifecycle`,不用 `@PostConstruct` / `@PreDestroy`。**
      Spring 先停止 Lifecycle bean、之後才銷毀 bean。用 `@PreDestroy` 停容器時,
      Redis 連線工廠可能已先被銷毀而輪詢執行緒還在跑,拋
      `RedisException: Connection closed` —— **錯誤指向連線,實際原因是關閉順序**。
      相位設為 `Integer.MAX_VALUE - 100`:最晚啟動、最早停止
- [x] 4.2c **踩到的 BUSYGROUP 判斷:訊息在最根本的 cause 裡,不在 Spring 包裝後的外層例外上。**
      檢查 `e.getMessage()` 會漏掉它,改用 `NestedExceptionUtils.getMostSpecificCause`。
      漏掉的症狀是「應用重啟後起不來」,而群組已存在本來就是重啟時的正常路徑
- [x] 4.3 `receive(...)` 而非 `receiveAutoAck(...)`:**落庫成功才 ack**。
      自動 ack 會在訊息「送達」時就確認,那等於把 pending 清單變成裝飾品 ——
      崩潰的訊息不會留下,對帳也就永遠不可能收斂
- [x] 4.4 重複落庫由**兩道**擋下:先查冪等鍵是否存在(常見路徑,不靠例外做流程控制),
      唯一約束為後盾(查詢與寫入之間的競態)。
      **約束違反刻意不在 `@Transactional` 方法內捕捉** —— 交易在例外發生時已被標記 rollback-only,
      在方法內攔下並正常回傳,提交階段會拋 `UnexpectedRollbackException`,
      一個看起來與原因完全無關的錯誤。讓它往外拋,由消費者判斷 ack 還是回補
- [x] 4.4b 新增 `OrderConstraints` 集中約束名稱,`GlobalExceptionHandler` 一併改用。
      **兩處的判斷必須一致** —— 只改其中一處的症狀是偶發的超賣
- [x] 4.5 `OrderPersistenceConsumerTest`:落庫後訂單出現且 pending 歸零;
      **同一則訊息送兩次只產生一筆訂單**。等待條件為「訂單出現」與「pending 歸零」,
      **不用固定 sleep** —— 固定等待在慢機器上會變成偶發失敗,而那看起來像測試不穩定
- [x] 4.5b **測試隔離:每個 context 用自己的 stream。**
      消費者在所有 Spring context 都會啟動,共用一條 stream 會讓某個測試投遞的訊息
      被另一個測試的消費者處理掉。另外契約測試**不再 TRUNCATE** ——
      清空資料表時消費者可能正在寫入,撞上外鍵違反後那則訊息會永遠留在 pending,
      **把 pending 汙染成一個無法解讀的數字,而後續的對帳驗收正是以 pending 為判準**
- [x] 4.6 **反向驗證,拆成兩步,結果都如預期**:

      **(a) 只拿掉「先查冪等鍵是否存在」→ 測試仍綠。**
      這是**正向**驗證:唯一約束的後盾單獨就守得住,兩道防線各自有效。

      **(b) 連約束判斷也拿掉 → 兩個測試都變紅。**
      重複訊息永遠不被 ack,`duplicatesBlocked` 不增加,pending 也排不掉 ——
      而在第 5 塊之後,同樣的路徑會觸發回補,**那就是超賣**。

      還原後 `./mvnw verify` 綠,`git status` 乾淨
- [x] 4.7 **驗證**:`./mvnw verify` 綠,126 個測試

## 5. 即時補償與週期對帳

- [x] 5.1 落庫失敗 → 回補庫存與已購數 → ack。回補以 `restore.lua` 原子完成
- [x] 5.1b **架構調整:落庫改為「非交易協調者 + 交易嘗試」兩個 bean。**
      原本打算讓消費者直接呼叫 `StockCachePort.restore` 補償,但那違反 D9 ——
      入站 adapter 不得直接使用 out port。改為
      `PersistPendingOrderService`(不標 `@Transactional`,協調與補償)
      + `PendingOrderPersistenceAttempt`(`@Transactional`,單次落庫),
      **與樂觀鎖那層同一個模式、同一個理由**:補償是 Redis 操作,
      它若落在資料庫交易內,會產生「交易回滾了但 Redis 已回補」的組合 —— 庫存被還兩次
- [x] 5.1c **約束違反改由持久化 adapter 翻譯成 `DuplicateOrderException`。**
      application 不得自己辨識 `DataIntegrityViolationException` 與約束名稱 ——
      那是持久化細節,而且 application 依賴 adapter 會違反分層方向。
      **這次改動打壞了既有的 `duplicateIdempotencyKey` 契約測試(409 變成 500)** ——
      新例外沒有對應的 handler,掉進了通用的 500。補上 handler 後恢復
- [x] 5.2 `ReconcileStockUseCase` + `ReconcileStockService`。
      差額 = (資料庫 `stock.available` − 快取餘量) − 資料庫訂單張數。
      **第 3 層不扣減資料庫的 `stock.available`,它保留為初始配額** —— 這是差額算式成立的前提
- [x] 5.3 **回補的前置條件是「積壓為空」,不是「pending 為空」。**

      **這是本塊最重要的修正,而且是我在寫驗收測試前才發現的設計漏洞。**
      原本(以及 design D5)寫的是「pending 為空時才回補」。但
      **`XPENDING` 只計算「已投遞給消費者、尚未 ack」的訊息** ——
      剛 `XADD` 進來、消費者還沒讀走的完全不在其中。

      後果很具體:消費者一旦落後(壓測時必然如此),就會出現
      「pending 為 0,但串流裡還躺著幾百則訊息」的瞬間。此時對帳看到差額、
      認定預扣已遺失、把庫存還回去 —— 而那些訊息稍後照樣落庫成功。
      **結果是真的超賣,而且只在高負載下出現。**

      改為新型別 `StreamBacklog(pending, hasUndelivered)`,後者以
      群組的 `last-delivered-id` 與串流的 `last-generated-id` 比對得出。
      **`isEmpty()` 兩者皆須為空** —— 唯有此時回補才安全
- [x] 5.4 未採用「把快取覆寫成 初始配額 − 訂單數」的對帳方式。回補一律是增量
- [x] 5.4b **對帳只回補庫存,不回補任何人的已購數**(新增 `restoreStockOnly`)。
      對帳算出的是聚合差額,它不知道那些扣減屬於誰。
      **這個不對稱是刻意接受的,而且方向是安全的**:那些使用者的限購額度仍被佔用著,
      他們會少買到票(保守);反過來猜測歸屬則可能讓某人超過限購。
      整合測試**明確斷言了這個不對稱**,而不是把它藏在註解裡
- [x] 5.5 `ReconciliationJob`(`@Scheduled`)+ `SchedulingConfig`。
      **只在策略為 `redisPreDeduct` 時執行** —— 對一個沒人在扣的快取做對帳沒有意義,
      而且會在其他三層的測試與壓測中製造無關的寫入
- [x] 5.6 測試:
      - `ReconcileStockServiceTest`(6 個,mock):完整決策表,含
        「有差額但積壓非空 → 不回補」與**「pending 為 0 但仍有未投遞 → 不回補」**
      - `CompensationAndReconciliationTest`(2 個,真實 Redis + PostgreSQL):
        補償路徑、對帳收斂、以及「已購數不被回補」的不對稱
- [x] 5.6b **落庫失敗以真實的外鍵違反注入** —— 對一個不存在於資料庫的場次投遞訊息。
      不用 mock、不在 production 留故障開關(D8):一個「能讓落庫失敗」的設定本身就是風險
- [x] 5.7 **反向驗證**:拿掉「積壓為空」的條件 →
      `doesNotRestoreWhilePendingIsNotEmpty` 變紅(`Expecting value to be false but was true`);
      還原後回綠
- [x] 5.7b **踩到的連線耗盡:`FATAL: sorry, too many clients already`。**
      第 2 支那個坑的放大版。真正的原因不是上限太低,而是
      **HikariCP 的 `minimumIdle` 預設等於 `maximumPoolSize`** ——
      正式設定上限 50,於是**每個 Spring test context 一啟動就預先開滿 50 條**,
      即使它只跑一個不碰資料庫的測試。context 快取又把它們全部留著。
      解法:測試環境以 `DynamicPropertyRegistrar` 把 `minimum-idle` 設為 2
      (**上限維持 50 不動** —— 那是壓測的測量條件,為了讓測試跑得過而改掉它,
      等於讓測試環境與被量測的環境不一致),並把容器的 `max_connections` 提高到 500 當緩衝。
      **不放進 `src/test/resources/application.yml`** —— 那會整份遮蔽主設定檔而非覆寫單一值
- [x] 5.8 production 未留任何故障注入開關
- [x] 5.9 **驗證**:`./mvnw verify` 綠,134 個測試

## 6. 併發正確性驗收

- [x] 6.1 `RedisPreDeductCorrectnessTest`(3 個,進入預設 `verify`,不加 tag):
      1000 併發搶 500 張 → **受理恰好 500、快取餘量恰好 0、資料庫訂單恰好 500**;
      同一人 20 併發 → 成交**恰好 4**;
      **正常流程下對帳不得補回任何庫存**(排程以 200ms 全程跑著)。
      判準與前三層相同 —— **「最終一致」是關於何時一致,不是關於是否一致**
- [x] 6.1b 第三個案例是原 task 沒有的。只在故障情境測對帳,等於沒驗過它平常在做什麼 ——
      **一個平時就在默默回補的對帳,會安靜地製造超賣**
- [x] 6.2 等待條件為「積壓為空且訂單數不再變動」,無固定 sleep
- [x] 6.3 **反向驗證三項,全部成立**:
      - 限購移出 Lua → **已於塊 2 驗證**(200 個併發全部成功,比 DB 層的超買 6 張極端得多)。
        不在 HTTP 層重跑:同一個缺陷,更低的層級已經給出更清楚的證據
      - 拿掉 `XADD` 失敗的回補 → `restoresWhenPublishFails` 變紅(`Wanted but not invoked`)
      - 拿掉對帳的「積壓為空」條件 → **已於塊 5 驗證**(`Expecting value to be false but was true`)
      每項還原後 `git status` 乾淨
- [x] 6.4 **驗證**:`./mvnw verify` 綠,143 個測試

## 7. 壓測與四層總表

- [x] 7.1 `k6/run-load-test.sh` 以 `redis-cli` 初始化快取庫存。
      **清空 stream 用 `XTRIM` 而非 `DEL`** —— `DEL` 會連 consumer group 一起刪掉,
      而應用正在跑,它的消費者會拿到 `NOGROUP` 並中止訂閱,症狀是「訂單再也不落庫」
- [x] 7.1b **修正一個會產生完全錯誤結論的判準。**
      既有腳本以「初始庫存 − 資料庫餘量」計算庫存減少量,再據以算超賣。
      **但第 3 層根本不扣資料庫的 `stock.available`** —— 沿用會算出「庫存減少 0、
      超賣 500 張」,而實際上零超賣。本層的餘量在 Redis,超賣仍是「售出超過初始配額」
- [x] 7.2 壓測後輸出對帳差額、快取餘量,以及**「k6 結束到訂單全部可見」的收斂時間**
- [x] 7.2b **踩到的量測 bug 兩個**:
      (1) k6 的 check 只認 `201`,第 3 層回 `202`,成功率被顯示成 25% ——
      **那是量測工具的錯,不是系統的**;
      (2) 收斂時間用 `date +%s000`,那其實是「秒 × 1000」,
      **所有低於一秒的耗時都顯示為 0**,看起來像「瞬間完成」。改以輪詢次數 × 間隔計算
- [x] 7.3 `RuntimeInfoLogger` 加入**對帳間隔**與 **Redis 位址** ——
      第 0/1/2 層完全不碰 Redis,四層並列時必須看得出哪一層多用了一個元件
- [x] 7.4 **四層數據(同一個 build、同一個 session、條件完全相同)。每層量測 2～3 次:**

      | | 策略 | req/s(各次) | 中位數 | avg | p(99) | 售出 | 超賣 |
      | --- | --- | --- | --- | --- | --- | --- | --- |
      | **A** | noLock | 494.02 / 681.73 / 852.15 | 681.73 | 349～721ms | 697ms～1.12s | 1000 | **966～984** |
      | **B** | pessimistic | 809.75 / 816.05 | 812.90 | 761～867ms | 1.17～1.18s | 500 | **0** |
      | **C** | optimistic | 462.82 / 473.59 | 468.21 | 1.51～1.67s | 2.05～2.11s | 500 | **0** |
      | **D** | redisPreDeduct | 522.23 / 648.76 / 771.13 | 648.76 | **266～443ms** | **490～850ms** | 500 | **0** |

      D 組另有:**對帳差額 0、快取餘量 0、落庫收斂 ≤ 200ms**、重複落庫 0。
      C 組:重試最大 48 次、平均 6.96、耗盡 0。

      **共同測量條件**:`availableProcessors: 4`、heap 1536MB、平台執行緒、連線池 50、
      對帳間隔 1000ms、重試上限 100、postgres 2 核 / 1GB / `max_connections=500` / tmpfs、
      **redis:7 2 核 / 1GB / 關閉 RDB 與 AOF(僅第 3 層使用)**、
      k6 2 核 / 512MB、1000 VU 每 VU 一次請求、初始配額 500、限購上限 4、macOS + OrbStack。

- [x] 7.5 **必須先講的一件事:我在第 7 支寫進 lessons 的「跨 session 雜訊約 4%」是錯的。**

      本次同一 session、同一設定連續量測:noLock 全距 **72%**(494→852)、
      redisPreDeduct 全距 **48%**(522→771)。4% 那個數字來自兩次悲觀鎖的比較 ——
      **而悲觀鎖恰好是最穩定的那一層**,我拿一個特例當成了通則。

      **穩定與否本身是有意義的訊號**:被硬性序列化點卡住的策略(悲觀鎖的列鎖、
      樂觀鎖的 CAS 競爭)吞吐極穩定,因為瓶頸在系統內部;
      沒有這種瓶頸的策略(無鎖、Redis 預扣)則隨機器當下的狀態擺盪。
      **前者量一次就夠,後者量一次等於沒量。**

- [x] 7.6 **四層對照與解讀:**

      **① 唯一能明確下的吞吐結論是「樂觀鎖最差」。** C 組(468)比 B 組(813)低約 42%,
      而兩者都極穩定,這個差距遠大於它們各自的擺盪。其餘三層的區間互相重疊,
      **本次數據不足以斷言誰的吞吐比較高** —— 包括不能說 Redis 預扣比悲觀鎖快。

      **② Redis 預扣真正贏的是延遲,而且贏很多。**
      avg 266～443ms vs 悲觀鎖 761～867ms;p99 490～850ms vs 1.17～1.18s。
      **這是它唯一在所有量測中都穩定勝出的指標。**

      **③ 但那個延遲優勢有一個必須講清楚的前提:k6 對第 3 層量到的是「受理速率」,
      不是「完成速率」。** 回應時訂單還不存在,工作只是被推遲了 ——
      這正是 design 中否決行程內佇列時寫下的理由(「佇列長度是隱形的背壓來源,
      會讓壓測的 req/s 被高估」),而它同樣適用於 Redis Stream,只是程度輕得多
      (收斂 ≤ 200ms,消費者跟得上)。**要誠實比較,req/s 必須連同收斂時間一起看。**

      **④ 為什麼吞吐沒有跟著延遲一起改善:因為總工作量沒有變少。**
      訂單照樣要寫進 PostgreSQL,只是換了時間點。非同步落庫縮短的是**使用者等待的時間**,
      不是**系統要做的事**。這一點與第 7 支「重試上限不是吞吐的旋鈕」是同一個道理:
      **只改變工作分佈的手段不會動到吞吐。**

- [x] 7.7 **對帳間隔定案為 1000ms**(design 的 Open Questions 結案)。
      實測收斂時間 ≤ 200ms,遠快於對帳週期 —— 也就是說**正常流程根本輪不到對帳出手**,
      它是故障時的兜底而不是常態機制。維持 1 秒:更短只會增加無謂的掃描,
      更長則在故障時拖慢收斂
- [x] 7.8 所有測量條件除刻意變動者外完全相同,無為了好看而調整。
      **A 組與 D 組的離群值照實列出,未挑選對自己有利的那一次**

## 8. 收尾

- [x] 8.1 完整驗證鏈:`./mvnw compile` BUILD SUCCESS、`./mvnw spotless:check` BUILD SUCCESS、
      `./mvnw verify` **Tests run: 143, Failures: 0, Errors: 0** / BUILD SUCCESS。
      `openspec validate --strict` 通過
- [x] 8.2 四層總表已填入 7.4～7.6,並同步寫入 `tasks/todo.md`
- [x] 8.2b **補上塊 3 欠的一筆**:`api-ticket-purchase` 的 202 範例原本寫
      `"orderId": null`,實作是「欄位不出現」。已更正為與實作一致,
      並寫明理由(沿用 `ApiResponse` 既有的判準:缺少 key 表示「沒有這個東西」,
      `null` 表示「有這個欄位但值為空」)。**文件與實作不符是最難查的一種錯**
- [x] 8.3 `tasks/todo.md`:第 8 支打勾、**Phase 1 完成**、四層總表寫入;
      新增三個延後項目 ——
      **Redis 高可用**(含 Cluster 的具體阻礙:Lua 腳本的兩個 key 必須同 slot,要改 hash tag)、
      **訂單查詢端點**(第 3 層回 202 只給冪等鍵,而目前沒有端點可用它 ——
      **這是 202 這個決定尚未付清的代價**)、
      **多實例的 consumer name**(目前寫死,共用會讓兩個實例互相認領 pending)
- [x] 8.4 `tasks/lessons.md`:**1 則更正 + 3 則新增**,都是真的踩到的:
      **(更正)「跨 session 雜訊約 4%」是錯的** —— 那個數字來自兩次悲觀鎖的比較,
      而悲觀鎖恰好是最穩定的一層,我拿特例當通則。實測全距:無鎖 72%、Redis 預扣 48%、
      悲觀鎖 0.8%、樂觀鎖 2.3%。**有內部序列化瓶頸的策略量一次就夠,沒有的量一次等於沒量。**
      **(新)`XPENDING` 看不到「還沒被讀走」的訊息** —— 對帳的安全條件因此寫錯過,
      而它只在高負載下顯現為超賣。
      **(新)HikariCP 的 `minimumIdle` 預設等於 `maximumPoolSize`** ——
      每個 test context 預先開滿 50 條連線;真正的原因不是上限太低。
      **(新)量測工具本身會說謊** —— k6 的 check 只認 201 讓第 3 層的成功率顯示成 25%,
      `date +%s000` 讓所有低於一秒的耗時顯示為 0
- [x] 8.5 `CLAUDE.md` 新增兩條 Hard Rule,皆標記〔**測試**〕且皆經反向驗證:
      **「積壓非空時不得回補快取庫存」**(含 backlog 的正確定義)與
      **「消費者不得把冪等鍵違反當成落庫失敗」**。
      兩者的違反後果都是超賣,而且都不會有任何錯誤訊息
- [x] 8.6 需要使用者手動執行的動作:壓測
      ```bash
      STRATEGY=redisPreDeduct POOL_SIZE=50 VIRTUAL_THREADS=false \
        docker compose --profile perf up -d --build --wait app postgres-perf redis-perf
      ./k6/run-load-test.sh          # 會自動載入快取庫存、等待落庫收斂、印出對帳差額
      docker compose --profile perf down
      ```
      **切換策略只需改 `STRATEGY` 後 `up -d --force-recreate --wait app`**,不必重建映像。
      **不穩定的層(noLock、redisPreDeduct)請連跑三次取中位數** —— 見 7.5
- [x] 8.7 archive 這支 change(`openspec archive add-redis-prededuct-strategy -y`)
