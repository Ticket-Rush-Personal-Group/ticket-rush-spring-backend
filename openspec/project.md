# ticket-rush backend — 專案索引

本檔是 backend repo 的**索引**:專案目的、repo 結構、技術棧,以及指向 `openspec/project/` 各分冊的導覽表。先讀本檔,再開你需要的那一份。

`CLAUDE.md` 只管 AI 的行為與工作流程,架構與慣例一律寫在這裡,兩邊不重複。

---

## 專案目的

高併發限量搶購系統。**核心不是「做一個搶票網站」,而是「同一個需求用四種併發控制策略實作,並用壓測數據比較它們的正確性與吞吐量」。**

雙重目標:補上 Java / Spring 生態(特別是 Node 單執行緒模型教不了的部分 —— 交易隔離級別、`@Transactional` 傳播行為、鎖策略、執行緒模型),以及產出可量化驗證的作品集成果。

**成功標準:** README 能放出四層策略 × 兩種執行緒模型的比較表,涵蓋正確性(是否超賣)與效能(QPS / P99 / 失敗率),且每個數字都有可重跑的 k6 腳本支撐。

任何無助於呈現併發策略差異的功能都不進 Phase 1。完整的範圍與排除清單見 Group repo 的整體 spec。

---

## repo 結構

本專案由三個獨立 repo 組成,分組資料夾為 `~/side_projects/Ticket-Rush-Personal-Group/`:

| repo | 內容 | 文件範圍 |
| --- | --- | --- |
| Group(分組層) | 整體設計 spec、入口 README | 跨端決策 |
| **`ticket-rush-spring-backend`(本 repo)** | REST API + Thymeleaf 管理介面 + k6 壓測 | 後端實作 |
| `ticket-rush-<框架>-frontend`(Phase 3) | 搶票頁 + 等候室 | 前端實作 |

**本 repo 不放前端規劃。** 前端於 Phase 3 建立,屆時該 repo 自建一份 openspec —— 跨 repo 的 change 不存在。

---

## 技術棧

| 層 | 選型 |
| --- | --- |
| 語言 | Java 21(虛擬執行緒為實驗維度之一) |
| 框架 | Spring Boot 4.1.1 |
| 建置 | Maven |
| DB | PostgreSQL 17 |
| Migration | Flyway |
| 快取 | Redis 7 |
| 管理介面 | Thymeleaf(Spring MVC) |
| 測試 | JUnit 5 + Testcontainers + ArchUnit |
| 壓測 | k6 |
| API 文件 | springdoc-openapi |

package 根:`com.alantsai.ticketrush`

---

## 架構速覽

六角架構(Ports & Adapters),分層命名沿用 `nexus-nest-backend`:

```
domain/         model, valueobject, policy, exception —— 無框架依賴
application/    port/in, port/out, service, facade
adapter/        in/web, in/scheduler, out/persistence, out/redis
infrastructure/ config, properties
```

**四層併發策略實作為 `PurchaseTicketUseCase` 的四個 application service**(不是單一 out port 的四個 adapter),由 `PurchaseFacade` 持有 `Map<String, PurchaseTicketUseCase>` 依設定選用。詳細論據見 `project/backend-architecture.md`。

---

## 分冊導覽

| 檔案 | 涵蓋 |
| --- | --- |
| `project/backend-architecture.md` | 六角分層、套件結構、四層策略的架構位置與論據、策略切換機制、domain 與 JPA entity 分離、命名慣例、五條不可違反的架構約束 |
| `project/backend-runtime.md` | 三種執行環境(開發 / 測試 / 壓測)的分工、共用資料庫的使用時機、壓測環境配置、JVM 容器資源感知、Dockerfile、壓測數據可信度聲明 |
| `project/openspec-conventions.md` | 自訂 schema、能力命名前綴(含本專案特有的 `strategy-`)、`strategy-*` 的驗收格式、change 命名、tasks.md 塊式切分、寫作品質基準 |

不要在 `CLAUDE.md` 重複以上任何內容。不確定就先讀本檔。
