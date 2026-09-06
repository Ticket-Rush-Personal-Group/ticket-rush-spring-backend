# Todo

> 跨 change 的待辦、需決定事項與延後功能。

## Phase 1 的 change 規劃

依序執行,每支一條 branch(從 `develop` 切)。切塊標準是能否獨立通過驗證鏈。

- [x] 1. `add-project-skeleton` —— **完成**。Maven + Spring Boot 4.1.1 + 六角目錄(14 個 package)+ ArchUnit guardrail(4 個 `@ArchTest`,各自經反向驗證)+ Spotless。`./mvnw verify` 綠,6 個測試通過
- [x] 2. `add-domain-model` —— **完成**。Flyway V1 三張表、domain model 與 value object、JPA entity 與手寫 mapper、三個基本 out port 與 persistence adapter、Testcontainers 整合測試。從第 1 支延後的兩條 ArchUnit 規則已補上並各自反向驗證(共 7 條規則)。`./mvnw verify` 綠,37 個測試
- [x] 3. `add-purchase-api-no-lock` —— **完成**。購票 API、`PurchaseFacade` 與策略選擇、第 0 層無鎖實作、統一 API wrapper 與錯誤映射、最後一條延後的 ArchUnit 規則(共 8 條)。`./mvnw verify` 綠,55 個測試。
      **超賣證據已取得:1000 併發搶 500 張,售出 1000 張、庫存只減少 144,超賣 856 張**(READ COMMITTED、未套資源限制)。這是 README 的開場數據
- [x] 4. `add-load-test-harness` —— **完成**(分支 `feat/add-load-test-harness`)。Dockerfile + compose `perf` profile + k6 腳本 + RuntimeInfoLogger。資源限制經反向驗證確實生效(cpus 4→2 時 JVM 報告跟著變)。第 0 層基準數據已取得:平台執行緒 p99 932.59ms / 超賣 862 張,虛擬執行緒 p99 872.88ms / 超賣 884 張
- [x] 5. `add-purchase-limit-policy` —— **完成**(分支 `feat/add-purchase-limit-policy`)。一人一場限購上限。限購規則置於 domain 且可純單元測試驗證,檢查順序為先限購後庫存。`./mvnw verify` 綠,82 個測試。**第二組證據已取得:20 個併發請求下超買 6 張**(上限 4)
- [x] 6. `add-pessimistic-lock-strategy` —— **完成**。第一個正確的實作。限購採「鎖前快篩 + 鎖後權威檢查」雙重檢查。`./mvnw verify` 綠,85 個測試(含兩個併發正確性**驗收**測試)。**零超賣、零超買;吞吐 693.76 req/s(無鎖同條件 827.58,-16%)。連線池對照判定瓶頸在連線池而非鎖(10→50 提升 36%)**
- [x] 7. `add-optimistic-lock-strategy` —— **完成**。條件式 UPDATE(`WHERE version = ?`)偵測衝突,
      重試迴圈置於交易之外(兩個 bean,避免 self-invocation)。讀取順序「先版本、後已購數」是正確性的一部分。
      `./mvnw verify` 綠,110 個測試。**零超賣、零超買;吞吐 475.64 req/s(悲觀鎖同條件 664.07,-28.4%),
      平均延遲 3.8 倍。重試分佈最大 48 次、平均 7.04 —— 每賣一張票伴隨約 13 次註定失敗的嘗試。**
      重試上限對照(100 vs 10)判定:**上限不是吞吐的旋鈕**,兩組吞吐與成交數皆相同,
      只有延遲與失敗語意改變。新增兩條 ArchUnit 守則,其中一條把 CLAUDE.md 原本「無測試能抓到」的
      self-invocation Hard Rule 變成了測試
- [ ] 8. `add-redis-prededuct-strategy` —— 驗收:零超賣 + 對帳收斂 + 注入落庫失敗後補償有效

**壓測 harness 刻意排在第 4 支而非最後:** 第 3 支完成後就有一個會超賣的版本,那正是 README 的開場數據。壓測能力若排在最後,第 6–8 支的驗收會缺少效能數字,而效能數字是每層驗收條件的一半。

## 需決定

- [ ] 前台框架(Vue 3 + Vite 或 Nuxt `ssr: false`)—— Phase 3 動工前決定,預設 Vue 3 + Vite。不論選哪個都不啟用 SSR
- [ ] 限購上限的實際張數 —— 暫定 4 張,壓測時可調整以觀察對競爭的影響

## 延後

- **重試退避(backoff)** —— 第 7 支刻意不做:重試風暴正是該層要量的現象,加退避會把它蓋掉。
  基準數據已取得(平均 7.04 次嘗試、最大 48 次、每張票伴隨約 13 次失敗嘗試)。
  **但第 7 支的上限對照顯示重試次數不是吞吐的瓶頸**(上限 100 與 10 的吞吐相同),
  因此退避能改善吞吐的機會不大 —— 若要做,目標應是延遲與資料庫負載,而不是吞吐

- **跨列鎖的順序** —— 目前只鎖單一庫存列,不會死鎖。若日後出現需要同時鎖多列的情境
  (例如跨場次套票),必須先定義鎖的取得順序,否則會死鎖

- **已取消訂單應排除於限購計算** —— 目前 `SUM(quantity)` 不篩選狀態。Phase 2 引入逾時取消後,
  已取消的訂單不應繼續佔用使用者的限購額度。現在只有 PENDING 狀態,該邏輯無法驗證,故不預先實作

- **Spring Security / 真實登入** —— Phase 2。橫切關注點,後加不需改架構;Phase 1 用 request header 帶 userId
- **訂單狀態機與逾時釋放庫存** —— Phase 2。逾時釋放是第三個併發考點(排程釋放與購買競爭同一列庫存)
- **Thymeleaf 管理介面** —— Phase 2,含策略即時切換與壓測儀表板
- **前台與 SSE 等候室** —— Phase 3,獨立 repo
- **部署與觀測(Micrometer / Prometheus / Grafana)** —— Phase 4

## Done

(尚無)
