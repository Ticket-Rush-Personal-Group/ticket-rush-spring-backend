## Why

搶票系統若不限制單人購買量,黃牛用腳本一次掃光庫存,限量的意義就消失了。這是真實票務平台的基本規則。

但本支的價值不只在規則本身。**限購是這個專案的第二個併發考點,而且它在無鎖策略下同樣會被突破** —— 檢查「這個人已經買了幾張」需要先讀取,兩個併發請求會讀到相同的已購數、各自通過檢查,又一次 lost update。

**這組證據比超賣更有說服力,因為它更難被發現。** 庫存超賣很明顯(總量對不上),但「某個人多買了兩張」不會讓任何總量出錯 —— 除非專門去查那個人。實務上這類缺陷可以存活很久。

**怎樣算做完:**

1. 單執行緒下限購有效:超過上限的請求回 `409 PURCHASE_LIMIT_EXCEEDED`,且不建立訂單、不扣庫存
2. **併發下限購被突破** —— 同一人同時發送超過上限的請求,實際成交量大於上限,且成因與超賣相同
3. 該證據以 `@Tag` 隔離,與超賣證據並列
4. 上限值可設定 —— 壓測時調整它會改變競爭形態

## What Changes

- 新增 `domain.policy.PurchaseLimitPolicy`:限購規則,純 Java 無框架依賴
- 新增領域例外 `PurchaseLimitExceededException`
- 新增 out port `LoadUserPurchasedQuantityPort`:查詢某人在某場次的已購張數
- `NoLockPurchaseService` 套用限購檢查
- 新增設定 `ticket-rush.max-tickets-per-user`(預設 4)
- 新增限購突破的證據測試,`@Tag("overselling-evidence")`
- `ErrorCode` 新增 `PURCHASE_LIMIT_EXCEEDED`

## Capabilities

### Modified Capabilities

- `api-ticket-purchase`:新增限購需求與 `PURCHASE_LIMIT_EXCEEDED` 失敗回應。**不開新 capability** —— 限購是購票端點的業務規則,照慣例併進既有 spec。
- `platform-api-response-format`:錯誤碼對應表新增一列。
- `strategy-no-lock`:補上「限購在無鎖下同樣被突破」這組證據,以及它與超賣的共同成因。

### New Capabilities

無。

## Impact

**新增相依:** 無。

**受影響的既有檔案:** `NoLockPurchaseService`(加入限購檢查)、`StockPersistenceAdapter` 或新增 order 側的 adapter 方法、`ErrorCode`、`GlobalExceptionHandler`、`application.yml`。

**資料庫:** 無 schema 變更 —— 查詢已購張數使用第 2 支已建立的 `idx_purchase_order_event_user` 索引。**那個索引當時是「已定案需求」而先行加入的,本支是它的第一個使用者。**

**需要使用者手動執行:** 無。
