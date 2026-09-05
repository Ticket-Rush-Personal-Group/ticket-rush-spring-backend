## ADDED Requirements

### Requirement: 庫存獨立於場次成表

庫存 MUST 儲存於獨立的 `stock` 表,以 `event_id` 為主鍵,而非作為 `event` 表的欄位。

`available` 是全系統競爭最激烈的一列資料。若它與場次名稱、開賣時間同表,鎖住庫存等同鎖住整列場次資料 —— 連讀取活動名稱都要排隊。這個切分本身就是併發設計的一部分,不是正規化的形式要求。

#### Scenario: 讀取場次資訊時不受庫存鎖影響

- **WHEN** 某交易正持有 `stock` 該列的鎖
- **THEN** 其他連線 SHALL 能正常讀取 `event` 表的對應資料而不被阻塞

---

### Requirement: 庫存不可為負,且規則定義於領域層

`stock.available` MUST 有 `CHECK (available >= 0)` 約束。同時,「扣減超過可用量」MUST 在 domain 層即被拒絕,不得依賴資料庫約束作為規則的定義處。

資料庫約束是基礎設施的最後防線;領域規則必須能在沒有資料庫的情況下,以純單元測試驗證。

**此約束不會影響無鎖對照組的超賣示範。** 無鎖策略的超賣成因是 lost update(多執行緒讀到相同的 `available`,各自計算後寫回同一個值),`available` 全程不會變成負數 —— 超賣的定義是「累計賣出張數超過總量」,不是「庫存為負」。

#### Scenario: 領域層拒絕超額扣減

- **WHEN** 對 `available` 為 3 的庫存扣減 5
- **THEN** domain 層 SHALL 拋出庫存不足的領域例外,且該行為 SHALL 能以不啟動資料庫的單元測試驗證

#### Scenario: 資料庫作為第二道防線

- **WHEN** 以 `UPDATE stock SET available = available - ?` 使結果為負值
- **THEN** 資料庫 SHALL 以 `CHECK` 約束拒絕該次更新

#### Scenario: lost update 造成的超賣不被 CHECK 攔截

- **WHEN** 兩個執行緒同時讀取 `available = 500`,各自計算 499 並寫回
- **THEN** `available` SHALL 為 499 且不觸發 `CHECK`,而累計賣出張數 SHALL 為 2 —— 這是無鎖對照組要呈現的現象

---

### Requirement: 訂單以冪等鍵防止重複建立

`purchase_order` MUST 有 `idempotency_key` 欄位並具備唯一約束,其值由客戶端產生。

在有重試機制的系統中這是必要條件而非加分項:樂觀鎖策略必然重試,Redis 預扣策略的補償機制也會重試,兩者都可能重複送出同一筆購買意圖。

#### Scenario: 相同冪等鍵的併發插入

- **WHEN** 兩個執行緒以相同的 `idempotency_key` 同時插入訂單
- **THEN** 資料庫 SHALL 只接受其中一筆,另一筆 SHALL 因唯一約束失敗

---

### Requirement: 資料表名稱不得使用 SQL 保留字

表名 MUST NOT 使用 SQL 保留字。訂單表命名為 `purchase_order`,不使用 `order`。

保留字需在每一次 JPQL、原生 SQL、psql 查詢中加引號,漏一次即為語法錯誤,而錯誤訊息通常指向下一個 token,不會指出保留字才是原因。

#### Scenario: 以未加引號的表名查詢

- **WHEN** 對訂單表執行未加引號的原生 SQL 查詢
- **THEN** 查詢 SHALL 正常執行,不因表名而失敗

---

### Requirement: domain model 與 JPA entity 為分離的型別

domain model MUST NOT 帶有任何 JPA 註解。持久化層 MUST 另有 `XxxJpaEntity`,兩者以 mapper 雙向轉換。

這是「四種併發策略切換、domain 零改動」這項主張的技術基礎。

entity 的**可見性邊界**(不得外洩至 `adapter.out.persistence` 之外)屬於架構約束,規範於 `platform-hexagonal-layering`,此處不重複。

#### Scenario: 雙向轉換保持資料一致

- **WHEN** domain model 轉為 JPA entity 後再轉回 domain model
- **THEN** 所有欄位值 SHALL 與原始物件相等

#### Scenario: domain 不帶持久化註解

- **WHEN** 檢視 `domain.model` 內的任何類別
- **THEN** SHALL 不存在 `jakarta.persistence` 的任何註解

---

### Requirement: 已套用的 Flyway migration 不得修改

已套用過的 migration 檔案 MUST NOT 被修改。schema 變更 MUST 以新增 `V<n>__<描述>.sql` 的方式進行。

修改已套用的檔案會使 checksum 不符,應用啟動即失敗。

#### Scenario: 修改既有 migration

- **WHEN** 修改已在資料庫留下紀錄的 migration 檔案內容
- **THEN** 應用啟動 SHALL 失敗並回報 checksum 不符

---

### Requirement: 整合測試使用真實資料庫且版本對齊

整合測試 MUST 以 Testcontainers 啟動真實的 PostgreSQL,版本 MUST 為 `postgres:17`,與 `~/dev-databases` 一致。MUST NOT 使用 H2。

H2 的鎖行為與 PostgreSQL 不同,以它測試鎖策略等同未測 —— 會得到測試全綠、上線超賣的系統。版本不一致則會產生「開發正常、測試失敗」這類難以定位的問題。

#### Scenario: 測試啟動時的資料庫來源

- **WHEN** 執行 `./mvnw verify`
- **THEN** 整合測試 SHALL 連線至 Testcontainers 啟動的 `postgres:17` 容器,而非共用的 `~/dev-databases`
