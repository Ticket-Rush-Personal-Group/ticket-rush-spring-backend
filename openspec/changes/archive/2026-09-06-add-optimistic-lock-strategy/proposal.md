## Why

第 6 支證明了正確性做得到,而且代價比預期小(吞吐 -16%)。但悲觀鎖的正確性來自**序列化** —— 所有人排隊,一次一個。本支要問的是相反方向的問題:**不排隊,讓大家同時做、衝突了再重來,結果會怎樣。**

樂觀鎖的假設是「衝突很少」。**而搶票正是這個假設最不成立的形狀** —— 1000 個人搶同一列庫存,衝突不是例外而是常態。因此本層的定位不是「更好的悲觀鎖」,而是**四層比較中的一個必要對照:它會示範一個正確的機制在錯誤的場景下如何失效**,以及失效的形態長什麼樣(重試風暴、成交率下降,而不是資料錯誤)。

本支還要處理一個第 6 支迴避掉的問題:悲觀鎖有一把排他鎖,所有檢查躲在後面就安全了;**樂觀鎖沒有鎖可躲**。版本號只保護 `stock` 那一列,而限購讀的是 `purchase_order` 的聚合。正確性因此改由「讀取順序」與「每次嘗試都是獨立交易」共同支撐 —— 兩者都沒有編譯期或框架層的保護,寫錯了不會有任何錯誤訊息。

**怎樣算做完:**

1. **零超賣**:1000 併發搶 500 張,售出恰好 500,庫存恰好 0(重試上限充足的前提下)
2. **零超買**:同一人併發下單,成交張數恰好等於限購上限
3. **重試次數分佈可得**,且分佈的最大值 > 1 —— 證明競爭真的發生過,不是靠沒有競爭而「正確」
4. **重試上限 100 vs 10 的對照**,量化重試風暴的代價
5. 效能數據與第 1 層在**相同測量條件**下對照

## What Changes

- 新增 out port `CompareAndDeductStockPort`:條件式 UPDATE(`WHERE version = ?`),回傳影響列數
- 新增 `OptimisticPurchaseService`(bean name `optimistic`,**不標 `@Transactional`**,持有重試迴圈)與 `OptimisticPurchaseAttempt`(標 `@Transactional`,單次嘗試)—— 拆成兩個 bean 是為了讓每次嘗試都是獨立交易,而**不能**靠同類別的自我呼叫達成
- 新增 `RetryStatistics`:行程內的重試次數直方圖,關閉時輸出摘要
- 新增錯誤碼 `RETRY_EXHAUSTED`(409),與 `INSUFFICIENT_STOCK` 明確區分
- 新增設定 `ticket-rush.optimistic.max-attempts`(預設 100),並納入測量條件
- 新增零超賣與零超買的併發正確性測試(**驗收測試,進入預設 `verify`**)
- 取得本層壓測數據:重試上限 100 與 10 兩組,並與第 1 層對照

## Capabilities

### New Capabilities

- `strategy-optimistic-lock`:第 2 層的驗收 —— 正確性(零超賣、零超買)、效能量測項目(含重試次數分佈與重試耗盡率)、與第 1 層的對照,以及「重試迴圈必須在交易之外」與「讀取順序決定正確性」這兩條沒有自動守則保護的約束。

### Modified Capabilities

- `platform-api-response-format`:新增錯誤碼 `RETRY_EXHAUSTED`。碼名依既有規定必須先於實作在 spec 定名。
- `platform-load-test-environment`:測量條件新增**重試上限**。它只對本層有意義,但既然會出現在數據表裡,就必須是條件的一部分 —— 少了它,上限 100 與上限 10 的兩組數字看起來會像是同一組條件下的矛盾結果。

## Impact

**新增相依:** 無。**刻意不引入 Spring Retry** —— 見 design D3。

**受影響的既有檔案:**

| 檔案 | 變更 |
| --- | --- |
| `ErrorCode` | 新增一個列舉值 |
| `GlobalExceptionHandler` | 新增一個 handler |
| `application.yml` | 新增 `ticket-rush.optimistic.max-attempts` |
| `StockJpaRepository` | 新增條件式 UPDATE 查詢方法 |
| `StockPersistenceAdapter` | 實作新的 port |
| `k6/run-load-test.sh` | 壓測後補讀重試分佈摘要 |
| `compose.yml` | 新增 `MAX_ATTEMPTS` 環境變數 |

**不受影響:** 第 0 層與第 1 層的實作**完全不動**。`StockJpaEntity` 也不動 —— **不標 `@Version`**,因為那會改變另外兩層的行為(design D1)。四層必須能在同一個建置中並存才能互相比較。

**無 schema 變更。** `stock.version` 在第 2 支就已建立並註明是為本支準備。

**需要使用者手動執行:** 壓測(方式與第 4、6 支相同,新增一個 `MAX_ATTEMPTS` 變數)。
