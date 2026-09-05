## Context

第 1 支留下一個能編譯、有 guardrail、但沒有任何資料的骨架。四層併發策略競爭的對象是庫存這一列資料,本支建立它。

同時償還第 1 支延後的兩條 ArchUnit 規則 —— 當時 spring-tx 與 JPA entity 都不在 classpath,連違規樣本都造不出來。本支引入 `spring-boot-starter-data-jpa` 後前提成立。

六角分層、domain 與 JPA entity 分離、三種執行環境的分工等既有決策見 `openspec/project/`,此處不重述。

## Goals / Non-Goals

**Goals:**

- Flyway migration 建立 `event` / `stock` / `purchase_order` 三張表
- domain model 與 value object,領域規則(庫存不可為負)在 domain 內
- JPA entity 與手寫 mapper,兩者型別分離
- 三個最基本的 out port 與其 persistence adapter 實作
- Testcontainers 整合測試,版本對齊 `postgres:17`
- 補上兩條 ArchUnit 規則並反向驗證

**Non-Goals:**

- **不做購票 API、facade、任何併發策略** —— 第 3 支起
- **不做限購政策** —— 第 5 支
- **不做訂單狀態機與逾時釋放** —— Phase 2
- **不定義策略專屬的 out port**(`LoadStockForUpdatePort`、`CompareAndDeductStockPort`、`StockCachePort`)—— 它們的形狀取決於各策略的實際需求,現在定義是猜測。port 跟著使用它的策略進來。

## Decisions

### D1. 表名用 `purchase_order`,不用 `order`

`order` 是 SQL 保留字,PostgreSQL 中必須寫成 `"order"` 才能使用。**代價不只是引號**:JPQL、原生 SQL、psql 手動查詢全都要記得加引號,漏一次就是語法錯誤,而錯誤訊息通常指向下一個 token,不會說「你用了保留字」。

**不選「保留 `order` 並加引號」**:為了貼近領域詞彙而讓每一次查詢都變成陷阱,不划算。`purchase_order` 同樣清楚。

### D2. `stock.available` 加 `CHECK (available >= 0)`,而且它不會破壞第 0 層的超賣示範

這是本支最需要說清楚的一點,因為直覺會認為兩者衝突。

**它們不衝突,因為第 0 層的超賣不是「扣成負數」,而是 lost update:**

```
執行緒 A 讀到 available = 500 → 計算 500 - 1 = 499 → 寫回 499
執行緒 B 讀到 available = 500 → 計算 500 - 1 = 499 → 寫回 499
結果:庫存 499,但賣出 2 張
```

`available` 全程沒有變成負數,`CHECK` 從頭到尾不會被觸發。超賣是「賣出張數 > 總量」,不是「庫存為負」——**最終庫存為 0 時累計賣出 683 張**,那就是第 0 層的示範數據。

反過來說,`CHECK` 對第 1、2 層(它們用 `UPDATE stock SET available = available - ? ` 由資料庫端計算)是有效的第二道防線:真的算到負數時會被擋下。**保留它沒有代價,拿掉則失去一道防線。**

### D3. domain model 用不可變 record,領域規則寫在 domain 內

`Stock.deduct(Quantity)` 回傳新的 `Stock`,庫存不足時拋 `InsufficientStockException`。

**領域規則不依賴資料庫約束。** `CHECK` 是基礎設施的防線,不是規則的定義處 —— domain 必須能在沒有資料庫的情況下用純單元測試驗證「扣超過庫存會失敗」。

**不選「可變的 entity 風格 domain class」**:可變狀態在併發情境下是額外的心智負擔,而這個專案的主題正是併發。不可變讓 domain 物件天生 thread-safe,把併發問題完全侷限在持久化邊界 —— 那正是我們想觀察的地方。

### D4. mapper 手寫,不引入 MapStruct

三個型別的雙向轉換手寫不到 50 行。

**不選 MapStruct**:它是 annotation processor,需要建置設定,而且產生的程式碼在 `target/` 內 —— 排查轉換錯誤時要去看產生物。用它換來的是省下 50 行明確的程式碼,不划算。若日後 entity 數量成長到十個以上再重新評估。

### D5. Testcontainers 用單一共用容器,不是每個測試類別一個

以 `@TestConfiguration` 提供 static container,所有整合測試共用。

**不選「每個測試類別各起一個容器」**:PostgreSQL 容器啟動約 1–3 秒,測試類別成長後會線性累加。本專案後續會有大量併發整合測試,這個成本會反覆支付。

**版本必須寫死 `postgres:17`**,與 `~/dev-databases` 一致 —— 版本不一致會產生「開發正常、測試失敗」這類最難定位的問題,而鎖的行為正是版本間可能有差異的部分。

### D6. 本支只定義三個最基本的 out port

`LoadEventPort`、`LoadStockPort`、`SaveOrderPort`,並實作其 persistence adapter。

有這三個,整合測試才能驗證完整路徑(port → adapter → JPA → 真實 PostgreSQL),而不是只測 entity 能不能存。

**不選「一次定義全部 port」**:策略專屬的 port 形狀取決於各策略的實際需求 —— 悲觀鎖需要 `SELECT ... FOR UPDATE`、樂觀鎖需要回傳影響列數。現在定義是猜測,而猜錯的介面會逼後面的策略去遷就它。

### D7. 兩條新 ArchUnit 規則的寫法

- **`@Transactional` 位置**:沿用第 1 支的字串 FQN 形式,類別與方法層級各一條。
- **JPA entity 不外洩**:以 `jakarta.persistence.Entity` 註解判定,**不用類別名稱結尾判定**。命名慣例可以被繞過(有人把 entity 取名 `OrderRecord`),註解不行。

兩條都必須反向驗證,且違規樣本要**同時涵蓋類別與方法層級**。

## Risks / Trade-offs

- **[`version` 與 `idempotency_key` 到第 7、3 支才使用,現在加入像是過度設計]** → 它們是已定案的需求而非預留彈性(規格寫在 Group repo 的 spec)。刻意延後只會在 migration 史多一個「當時故意不加」的檔案,對讀演進史的人是雜訊。
- **[Testcontainers 首次執行需下載 `postgres:17` 映像]** → 一次性成本;`~/dev-databases` 已在使用同一版本,映像多半已在本機。
- **[共用容器讓測試間可能互相污染]** → 每個測試類別自行清理其建立的資料,或以交易回滾隔離。這一點要在 tasks 明確驗證,不能只靠約定。
- **[Boot 4 的相依名稱與 3.x 不同]** → 已用 Initializr 實測確認(見 proposal 的表格),不依賴記憶或網路上的 3.x 範例。

## Migration Plan

新專案,無既有資料。`V1__init_schema.sql` 為第一份 migration。

**Flyway 的鐵則:已套用的 migration 不得修改** —— checksum 不符會使應用啟動失敗。schema 要改一律新增 `V2__...`。這條寫進 CLAUDE.md 的 Hard Rules 已生效。

## Open Questions

無。`@ServiceConnection` 在 Boot 4 的實際 package 位置於實作時以 jar 內容確認(前一支的經驗:Boot 4 的 package 搬家不能用猜的)。
