# backend 執行環境

> 開發 / 測試 / 壓測三種環境的分工、共用資料庫的使用時機、壓測環境配置與 JVM 容器資源感知、Dockerfile、壓測數據可信度聲明。

> 本檔為 `openspec/project.md` 的一部分,導覽見該檔。
> 程式碼架構見 `backend-architecture.md`;整體專案 spec 見 Group repo。

---

## 1. 三種執行環境

本專案有三個彼此獨立的執行環境,各自的需求互相衝突,因此不共用設定。

| 環境 | 用途 | 資料庫來源 | 資料庫名 | 應用執行位置 |
|---|---|---|---|---|
| 開發 | 起應用手動操作 | **既有的 `~/dev-databases`(共用)** | `ticket_rush_db` | host |
| 測試 | 併發正確性測試 | **Testcontainers 自行啟動** | `ticket_rush_test` | host(JUnit) |
| 壓測 | 取得八組效能數據 | **compose `perf` profile 自行啟動** | `ticket_rush_db` | container |

資料庫命名沿用既有慣例 `<專案名>_db` / `<專案名>_test`(參照 `nexus_db` / `nexus_test`)。壓測環境為獨立容器,無撞名風險,故沿用 `ticket_rush_db` 使其設定與開發環境一致。

**分工的關鍵:** 真正會對資料庫施壓的兩個場景(併發測試、壓測)本來就不使用共用資料庫。開發環境剩下的只是單人操作的低併發情境,共用資料庫已足夠。

---

## 2. 開發環境

### 使用既有的共用資料庫

`~/dev-databases` 已提供 `postgres:17`(5432)與 `redis:7`(6379),直接連用,本專案不另起開發用容器。

初次設定:

```bash
docker exec my-postgres createdb -U postgres ticket_rush_db
```

### 應用跑在 host,不進 container

Node 專案容器化開發的主要收益,是消除原生模組的平台相依風險(如 bcrypt、Prisma engine 的 `invalid ELF header`)。**Java 不存在此類問題** —— jar 平台無關,Maven 依賴亦無原生二進位相容性顧慮。

收益消失,成本卻更高:

- JVM 啟動較慢,DevTools 重啟在容器內更慢
- IDE debug 需接遠端 debug port
- macOS 的 bind mount I/O 較慢,會拖慢 Maven 編譯

因此開發時應用跑在 host。

### 禁止事項

**不得在共用資料庫上執行併發測試。** 其 `max_connections` 為預設值 100,悲觀鎖策略會迅速耗盡連線,連帶影響其他使用 5432 的專案。所有併發驗證一律走 Testcontainers。

---

## 3. 測試環境

由 Testcontainers 自行啟動 PostgreSQL 與 Redis 容器,與開發及壓測環境完全隔離。

**版本必須對齊 `~/dev-databases`:`postgres:17`、`redis:7`。** 版本不一致會導致「開發正常、測試失敗」這類難以定位的問題,而鎖的行為正是版本間可能有差異的部分。

Testcontainers 的資料庫名須明確指定為 `ticket_rush_test`(`PostgreSQLContainer` 的預設值為 `test`),與既有命名慣例一致。

不使用 H2 的理由見整體 spec。

---

## 4. 壓測環境

### 應用進 container,並限制資源

與開發環境的判斷相反,理由來自虛擬執行緒:

**虛擬執行緒的 carrier thread 數量預設等於 `Runtime.availableProcessors()`。**

- 跑在 host:該值為整台機器的核心數,且 k6、IDE、瀏覽器共同競爭 —— 每次執行的有效並行度都不同
- 跑在 container 並設定 `cpus`:JVM 透過 cgroup 讀到固定值,每次一致

本專案的核心產出之一即為虛擬執行緒與平台執行緒的對照。若並行度本身在浮動,該組數據不具意義。

**資源限制的目的是讓八組數據可互相比較,而非模擬正式環境。**

### JVM 的容器資源感知

JDK 10+ 預設啟用 `UseContainerSupport`,JVM 會讀取 cgroup 限制。而 `MaxRAMPercentage` 預設僅 **25%** —— 容器給 2GB,heap 只有 512MB,壓測時 GC 頻繁到數據失真,**且不會產生任何錯誤訊息**。

壓測容器必須明確設定 heap:

```yaml
environment:
  JAVA_TOOL_OPTIONS: "-XX:MaxRAMPercentage=75"
```

### PostgreSQL 參數

**`max_connections` 必須調高**(建議 500)。預設 100 會使悲觀鎖策略先撞上連線耗盡 —— 該現象在圖表上與鎖等待極為相似,將導致對第 1 層的錯誤結論。

**`fsync` 不得關閉。** 關閉 fsync 可減少磁碟噪音,但其影響對各策略不均等:悲觀鎖的持鎖時間包含 commit 的 fsync,關閉等同單方面偏袒該策略,破壞相對比較的有效性。

資料使用 tmpfs,每次壓測從乾淨狀態開始。

### 無需對外埠

k6 與應用位於同一 compose network,k6 直接以服務名稱連線(`http://app:8080`)。**perf profile 的所有服務皆不發布 host 埠**,因此不存在與 `dev-databases`(5432 / 6379)或 nexus(5442 / 6389)的埠衝突。

### compose 骨架

```yaml
name: ticket-rush

services:
  postgres-perf:
    profiles: [perf]
    image: postgres:17
    command: postgres -c max_connections=500
    environment:
      POSTGRES_PASSWORD: perf
      POSTGRES_DB: ticket_rush_db
      PGDATA: /var/lib/postgresql/data/pgdata
    tmpfs:
      - /var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U postgres -d ticket_rush_db"]
      interval: 2s
      retries: 30

  redis-perf:
    profiles: [perf]
    image: redis:7
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 2s
      retries: 30

  app:
    profiles: [perf]
    build:
      context: .
    cpus: 4
    mem_limit: 2g
    environment:
      JAVA_TOOL_OPTIONS: "-XX:MaxRAMPercentage=75"
    depends_on:
      postgres-perf: { condition: service_healthy }
      redis-perf: { condition: service_healthy }

  k6:
    profiles: [perf]
    image: grafana/k6
    cpus: 2
    depends_on:
      app: { condition: service_healthy }
```

`healthcheck` 一律檢查應用的健康端點,而非行程或埠 —— 容器 running 與「可接受請求」之間存在實質空窗。

---

## 5. Dockerfile

多階段建置,並使用 Spring Boot 的 layered jar:

- **建置階段:** `maven:3.9-eclipse-temurin-21`
- **執行階段:** `eclipse-temurin:21-jre`

Spring Boot 的 `layertools` 可將 jar 拆分為依賴層與應用層。壓測需反覆重建映像,修改應用程式碼時僅重建最上層,差異顯著。

---

## 6. 壓測數據的可信度聲明

在 macOS 的 OrbStack / Docker Desktop 上執行壓測,**絕對數字不代表生產環境效能**:存在虛擬化層與網路開銷,且 k6 與被測系統共用同一台機器的 CPU。

但本專案需要的是**四層策略之間的相對比較**。只要環境一致,相對關係即成立 —— 這正是採用固定資源限制的目的。

**此聲明必須寫入 README。** 主動說明測量條件的限制,勝過被質疑後才承認。

---

## 7. 沿用自 nexus 的慣例

- 單一 compose 檔配合 profile 區分用途,不使用多份檔案
- compose project 明確命名(`name:`)
- healthcheck 使用對應工具(`pg_isready -U -d`),不檢查行程或埠
- `depends_on` 等待 `service_healthy`
- 可拋棄的資料使用 tmpfs,需保留的使用 named volume
- 註解記錄決策的理由與踩過的坑,而非複述設定內容

**不沿用的部分:** node_modules 的 named volume 覆蓋策略(Java 無對應問題)、對外埠避開預設值(perf profile 不發布任何 host 埠)。

---

## 8. 決策紀錄

| 決策 | 選擇 | 理由 |
|---|---|---|
| 資料庫命名 | `ticket_rush_db` / `ticket_rush_test` | 沿用既有慣例 `<專案名>_db` / `<專案名>_test`(參照 `nexus_db` / `nexus_test`) |
| 開發資料庫 | 共用 `~/dev-databases` | 開發為單人低併發;施壓場景本就不使用它 |
| 開發時應用位置 | host | Java 無原生模組相依問題,容器化收益不足以抵銷成本 |
| 測試資料庫 | Testcontainers | 隔離、每次乾淨;版本對齊 `postgres:17` / `redis:7` |
| 壓測時應用位置 | container 並限制資源 | 固定 `availableProcessors`,虛擬執行緒對照才有效 |
| 壓測 heap | 明確設定 `MaxRAMPercentage=75` | 預設 25% 會導致 GC 失真且無錯誤訊息 |
| `max_connections` | 500 | 預設 100 會使悲觀鎖先撞連線耗盡,而非鎖等待 |
| `fsync` | 保持開啟 | 關閉會不均等地偏袒悲觀鎖 |
| perf 對外埠 | 完全不發布 | k6 於同一 network 內連線,消除埠衝突 |
