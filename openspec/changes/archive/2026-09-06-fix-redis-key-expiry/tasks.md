> 每一塊(`##` 標題)須能獨立通過驗證鏈:
> `./mvnw compile && ./mvnw spotless:check && ./mvnw verify`
> **既有 143 個測試必須全程維持綠。本支不得改變任何併發行為** ——
> 預扣、落庫、補償、對帳的邏輯都不碰,只加生命週期。
> 每塊綠燈才進下一塊。**不逐塊 commit** —— 一支 change 一個 commit,收尾塊(塊 3)才給指令。
>
> 塊的依賴關係:
> - 塊 1(Lua 的期限繼承)獨立,先做。
> - 塊 2(載入端與警告)依賴塊 1 的 port 變更。

## 1. Lua 的期限繼承與緩衝

- [x] 1.1 `pre-deduct.lua` 在 `INCRBY purchased` 之後:以 `EXPIRETIME KEYS[1]` 讀出
      `stock` 的**絕對**到期時間,再 `EXPIREAT KEYS[2]` 套到 `purchased` 並加上緩衝。
      **繼承而非讓呼叫端各自指定** —— 分別指定等於把安全條件交給每個呼叫端記得
- [x] 1.2 `stock` 沒有過期時間(`EXPIRETIME` 回 `-1`)時,`purchased` 也不設 ——
      保持一致,不走向不安全的方向
- [x] 1.3 緩衝以 `StockCacheAdapter` 的**常數**傳入,**不做成設定**:
      它是安全邊界不是可調參數,做成設定只會讓某天有人把它調成 0。
      值取 1 小時 —— 它只需要大於 Redis 惰性過期的不確定性(秒等級)
- [x] 1.4 **測試(對真實 Redis,新增 4 個)**:
      - `stock` 有 TTL → 預扣後 `purchased` 有 TTL,且**到期時間晚於 `stock`**(不是相同)
      - `stock` 無 TTL → `purchased` 也無 TTL
      - **回補後 `stock` 的 TTL 仍在** —— 這條守的不是我們的邏輯,
        是「`INCRBY` 保留 TTL」這個我們對 Redis 的假設
- [x] 1.5 **反向驗證成立**:移除腳本尾端的 `EXPIREAT` →
      `purchasedExpiresAfterStock` 變紅(「已購數必須有過期時間」);還原後回綠,`git status` 乾淨
- [x] 1.6 **驗證**:`./mvnw verify` 綠

## 2. 載入端的過期時間與遺漏警告

- [x] 2.1 `k6/run-load-test.sh` 載入快取庫存時帶上過期時間(`SET ... EX`),
      秒數以可覆寫的變數表達
- [x] 2.2 新增 `StockCachePort.hasExpiry(EventId)`,adapter 以 `getExpire` 實作
- [x] 2.3 `ReconcileStockService` 在對帳時,對沒有過期時間的 `stock` key 發出 WARN。
      **沿用既有的 `eventsOnSale()` 掃描,不另外掃一遍**
- [x] 2.4 **不得以拒絕預扣的方式處理遺漏** —— 那會把「慢慢洩漏」變成「完全不能賣票」,
      用一個更嚴重的故障去防一個較輕的問題
- [x] 2.5 測試:`hasExpiry` 對有 / 無 TTL 的 key 分別回傳正確結果。
      **WARN 本身不寫測試** —— 斷言 log 內容很脆,而它是觀測輔助不是正確性
- [x] 2.5b **追加反向驗證(原 task 沒有)**:把 `restoreStockOnly` 改寫成
      「讀出來、算好、`SET` 回去」—— 一個看起來完全合理的重構 ——
      `restorePreservesExpiry` 立刻變紅。**這條測試守的不是本專案的邏輯,
      是本專案對 Redis 的假設**(`INCRBY` 保留 TTL、`SET` 清除它)。
      做這項是因為要把它寫進 Hard Rules 並標記〔測試〕,而那個標記必須先被證明
- [x] 2.6 **驗證**:`./mvnw verify` 綠

## 3. 收尾

- [x] 3.1 完整驗證鏈:`compile` BUILD SUCCESS、`spotless:check` BUILD SUCCESS、
      `verify` **Tests run: 147, Failures: 0, Errors: 0** / BUILD SUCCESS
- [x] 3.2 `tasks/todo.md`:本支打勾;新增兩個延後項目 ——
      **「以場次結束時間取代快取保存期限」**(D2 的落差)與
      **「快取 TTL 的設定責任應移進應用」**(目前靠警告,而警告會被忽略)
- [x] 3.3 `tasks/lessons.md` 寫入一則:**「Redis 的過期有兩件事跟直覺不一樣」**。
      這是真的踩到的 —— 我原本的設計就是「兩者設成相同到期時間就安全了」,
      而那不成立(惰性 + 抽樣的過期不保證同時消失)。
      同一則另記 `INCRBY` 會建立無 TTL 的新 key、以及
      **寫入指令對 TTL 的處置不一致**(`INCRBY` 保留、`SET` 清除)
- [x] 3.4 `CLAUDE.md` 新增一條 Hard Rule:**「回補不得用 `SET`」**,標記〔**測試**〕。
      在 2.5b 通過反向驗證之後才寫入 —— **標錯強制方式比不寫更糟**
- [x] 3.5 需要使用者手動執行的動作:無(壓測腳本的變更會自動生效)
- [x] 3.6 archive 這支 change(`openspec archive fix-redis-key-expiry -y`)
