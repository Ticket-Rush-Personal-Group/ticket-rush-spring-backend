## Why

第 8 支把庫存搬進 Redis,但**沒有任何一個 key 設過期時間**。
`purchased:{eventId}:{userId}` 是**每個買過票的人一個 key** —— 一場一萬人搶的活動留下一萬個,
活動結束後它們仍然在。這是無界的記憶體成長。

**這個缺陷不會被現有的任何機制發現:** 測試裡 key 少、生命週期短,行為完全正確;
Redis 不會報錯,只是慢慢長大;壓測每次重建容器,key 從零開始。
它會一直安靜下去,直到某天撞上 `maxmemory`。

修正本身不難,難的是**過期的順序**:`purchased` 若先於 `stock` 過期,
該使用者的限購額度歸零,可以重新買一輪 —— **直接超買**。
而「設成同時到期」並不安全,因為 Redis 的過期是惰性 + 抽樣的,不保證兩個 key 同時消失。

**怎樣算做完:**

1. `stock` 與 `purchased` 都有過期時間,且 **`purchased` 確定晚於 `stock`**
2. `stock` 沒有過期時間時,`purchased` 也不設 —— 一致,不會走到不安全的方向
3. **回補之後過期時間仍在** —— 釘住「`INCRBY` 保留 TTL」這個我們依賴的 Redis 行為
4. 忘記設定過期時間時**有警告**,不是安靜地繼續洩漏

## What Changes

- `pre-deduct.lua` 在 `INCRBY purchased` 之後,以 `EXPIRETIME` 讀出 `stock` 的絕對到期時間,
  `EXPIREAT` 套到 `purchased` 並加上安全緩衝
- `StockCacheAdapter` 傳入緩衝秒數(**常數而非設定** —— 它是安全邊界,不是可調參數)
- 新增 `StockCachePort.hasExpiry(EventId)`;`ReconcileStockService` 在對帳時對
  沒有過期時間的 `stock` key 發出 WARN
- `k6/run-load-test.sh` 載入快取庫存時帶上過期時間(`SET ... EX`)
- 新增測試:繼承關係、緩衝方向、無 TTL 時的一致性、**回補後 TTL 仍在**

## Capabilities

### Modified Capabilities

- `strategy-redis-prededuct`:新增一條需求「快取 key 的生命週期」——
  過期順序的安全性要求、繼承關係、以及「保存期限不等於場次結束時間」這個已知落差。

## Impact

**新增相依:** 無。**無 schema 變更。**

**受影響的既有檔案:**

| 檔案 | 變更 |
| --- | --- |
| `pre-deduct.lua` | 尾端新增期限繼承 |
| `StockCachePort` / `StockCacheAdapter` | 新增 `hasExpiry`;預扣多傳一個緩衝參數 |
| `ReconcileStockService` | 對帳時檢查並警告 |
| `k6/run-load-test.sh` | 載入時帶過期時間 |
| `StockCacheBehaviourTest` | 新增生命週期相關案例 |

**不受影響:** 第 8 支的併發行為完全不動 —— 預扣、落庫、補償、對帳的邏輯都不碰。
第 0 / 1 / 2 層完全不涉及。

**已知且刻意保留的限制:** 過期時間是「快取保存期限」而非「場次結束時間」——
schema 沒有後者,而為了 TTL 新增 domain 欄位是 Phase 1 排除的範圍蔓延。
銷售期若超過該期限,場次會在銷售中變成「未開賣」:**fail-closed 的停擺,不是資料錯誤**。
Phase 2 有場次狀態時應改為以結束時間為準。

**需要使用者手動執行:** 無。
