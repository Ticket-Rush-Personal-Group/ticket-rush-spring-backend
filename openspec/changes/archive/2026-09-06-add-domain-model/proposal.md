## Why

第 1 支建立的骨架沒有任何資料。四層併發策略競爭的對象是**庫存這一列資料**,沒有它,後續六支 change 全部無法開始。

本支同時償還第 1 支刻意留下的技術債:`@Transactional` 位置守則與 JPA entity 外洩守則,當時因為 spring-tx 與 entity 都不在 classpath 而無法反向驗證,已記錄延後至本支。**本支引入 `spring-boot-starter-data-jpa` 後,兩者的前提都成立。**

**怎樣算做完:**

1. Testcontainers 起真實的 `postgres:17`,Flyway migration 套用成功,三張表結構符合 spec
2. domain model 與 JPA entity 的 mapper 雙向轉換,轉換後所有欄位相等
3. `idempotency_key` 的唯一約束在併發插入下確實擋住重複訂單
4. 兩條新增的 ArchUnit 規則各自經反向驗證確認會紅

## What Changes

- 新增 Flyway migration:`event`、`stock`、`order` 三張表
- 新增 domain model:`Event`、`Stock`、`Order`,以及對應的 value object
- 新增 JPA entity(`XxxJpaEntity`)與 domain ⇄ entity 的 mapper
- 新增 out port 介面定義(僅介面,實作屬於第 3 支起的各策略)
- 新增 Testcontainers 整合測試,版本對齊 `~/dev-databases` 的 `postgres:17`
- **補上兩條從第 1 支延後的 ArchUnit 規則**,各自反向驗證

**schema 一次包含 `version` 與 `idempotency_key`。** 這兩個欄位分別到第 7、3 支才被使用,但它們不是預留彈性而是**已定案的需求** —— 四層策略的規格已寫死在 Group repo 的 spec 裡。刻意延後只會在 migration 史裡多一個「當時故意不加」的檔案,對日後讀 migration 演進的人是雜訊。

## Capabilities

### New Capabilities

- `platform-persistence-model`:三張表的結構與欄位語意、Stock 獨立成表的理由、domain 與 JPA entity 的對應規則、Flyway migration 的命名與不可修改原則、測試資料庫的版本對齊要求。

### Modified Capabilities

- `platform-hexagonal-layering`:新增兩條 ArchUnit 守則的需求 —— `@Transactional` 不得出現在 adapter、JPA entity 不得外洩 `adapter.out.persistence`。同時更新「架構約束必須標明強制方式」表格中這兩條的納入時機。

## Impact

**新增相依**(名稱已用 Initializr 實測確認,Boot 4 與 3.x 有差異):

| 用途 | artifact | 與 3.x 的差異 |
| --- | --- | --- |
| JPA | `spring-boot-starter-data-jpa` | 相同 |
| Flyway | **`spring-boot-starter-flyway`** | 3.x 直接依賴 `flyway-core` |
| Flyway PG 方言 | `flyway-database-postgresql` | 相同 |
| PG driver | `postgresql`(runtime) | 相同 |
| 測試 | `spring-boot-starter-data-jpa-test`、`spring-boot-starter-flyway-test` | Boot 4 的 `-test` 拆分 |
| Testcontainers | `spring-boot-testcontainers`、**`testcontainers-junit-jupiter`**、**`testcontainers-postgresql`** | 後兩者傳統上是 `junit-jupiter` / `postgresql` |

**受影響的既有檔案:** `pom.xml`、`application.yml`(資料來源設定)、`HexagonalLayeringTest`(補兩條規則)、`openspec/specs/platform-hexagonal-layering/spec.md`(archive 時合併)。

**需要使用者手動執行:** 開發環境需在共用資料庫建立 schema —— `docker exec my-postgres createdb -U postgres ticket_rush_db`。整合測試不需要,Testcontainers 自行啟動容器。
