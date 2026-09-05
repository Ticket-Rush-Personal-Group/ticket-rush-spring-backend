# Todo

> 跨 change 的待辦、需決定事項與延後功能。

## Phase 1 的 change 規劃

依序執行,每支一條 branch(從 `develop` 切)。切塊標準是能否獨立通過驗證鏈。

- [x] 1. `add-project-skeleton` —— **完成**。Maven + Spring Boot 4.1.1 + 六角目錄(14 個 package)+ ArchUnit guardrail(4 個 `@ArchTest`,各自經反向驗證)+ Spotless。`./mvnw verify` 綠,6 個測試通過
- [x] 2. `add-domain-model` —— **完成**。Flyway V1 三張表、domain model 與 value object、JPA entity 與手寫 mapper、三個基本 out port 與 persistence adapter、Testcontainers 整合測試。從第 1 支延後的兩條 ArchUnit 規則已補上並各自反向驗證(共 7 條規則)。`./mvnw verify` 綠,37 個測試
- [x] 3. `add-purchase-api-no-lock` —— **完成**。購票 API、`PurchaseFacade` 與策略選擇、第 0 層無鎖實作、統一 API wrapper 與錯誤映射、最後一條延後的 ArchUnit 規則(共 8 條)。`./mvnw verify` 綠,55 個測試。
      **超賣證據已取得:1000 併發搶 500 張,售出 1000 張、庫存只減少 144,超賣 856 張**(READ COMMITTED、未套資源限制)。這是 README 的開場數據
- [ ] 4. `add-load-test-harness` —— k6 腳本 + compose `perf` profile。驗收:量得到第 0 層的超賣數據(README 的開場圖)
- [ ] 5. `add-purchase-limit-policy` —— 一人一場限購上限。驗收:同一人並發請求擋得住
- [ ] 6. `add-pessimistic-lock-strategy` —— 驗收:零超賣 + 鎖等待與連線池飽和點數據
- [ ] 7. `add-optimistic-lock-strategy` —— 驗收:零超賣 + 重試次數分佈
- [ ] 8. `add-redis-prededuct-strategy` —— 驗收:零超賣 + 對帳收斂 + 注入落庫失敗後補償有效

**壓測 harness 刻意排在第 4 支而非最後:** 第 3 支完成後就有一個會超賣的版本,那正是 README 的開場數據。壓測能力若排在最後,第 6–8 支的驗收會缺少效能數字,而效能數字是每層驗收條件的一半。

## 需決定

- [ ] 前台框架(Vue 3 + Vite 或 Nuxt `ssr: false`)—— Phase 3 動工前決定,預設 Vue 3 + Vite。不論選哪個都不啟用 SSR
- [ ] 限購上限的實際張數 —— 暫定 4 張,壓測時可調整以觀察對競爭的影響

## 延後

- **Spring Security / 真實登入** —— Phase 2。橫切關注點,後加不需改架構;Phase 1 用 request header 帶 userId
- **訂單狀態機與逾時釋放庫存** —— Phase 2。逾時釋放是第三個併發考點(排程釋放與購買競爭同一列庫存)
- **Thymeleaf 管理介面** —— Phase 2,含策略即時切換與壓測儀表板
- **前台與 SSE 等候室** —— Phase 3,獨立 repo
- **部署與觀測(Micrometer / Prometheus / Grafana)** —— Phase 4

## Done

(尚無)
