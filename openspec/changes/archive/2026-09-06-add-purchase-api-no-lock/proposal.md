## Why

前兩支建立了骨架與資料模型,但系統還沒有任何業務行為。本支打通購票的完整路徑:HTTP → facade → use case → out port → PostgreSQL。

更重要的是,本支要產出**問題確實存在的證據**。第 0 層無鎖對照組不是佔位用的空實作,它是整個專案的敘事起點:沒有「1000 併發搶 500 張、實際賣出 683 張」這個數字,後面三支策略的價值無從量化,README 也沒有開場。

本支同時償還最後一條延後的架構守則:「禁止單獨注入 `PurchaseTicketUseCase`」—— 該介面在本支首次出現,前提終於成立。

**怎樣算做完:**

1. 單執行緒購票正確:庫存扣減、訂單建立、回應符合統一 wrapper
2. **併發下確實超賣**:1000 執行緒搶 500 張,累計售出張數 **大於** 500,且 `stock.available` 全程 **不為負** —— 後者證明成因是 lost update 而非扣成負數
3. 超賣測試以獨立 tag 隔離,不阻斷 CI,但可單獨執行重現
4. 「禁止單獨注入 `PurchaseTicketUseCase`」規則補上並經反向驗證

## What Changes

- 新增 in port:`PurchaseTicketUseCase` 與 `PurchaseTicketCommand`
- 新增 application service:`NoLockPurchaseService`(第 0 層)
- 新增 `PurchaseFacade` 與策略選擇機制(`Map<String, PurchaseTicketUseCase>`)
- 新增 `adapter.in.web`:`PurchaseController`、Request/Response DTO
- 新增統一 API wrapper 與領域例外到 HTTP 的映射
- 新增 out port:`UpdateStockPort`(無鎖更新專用)
- 新增 ArchUnit 規則:禁止單獨注入 `PurchaseTicketUseCase`
- 新增超賣證據測試,以 `@Tag` 隔離

**第 0 層有 `@Transactional` 但沒有鎖。** 這不是疏漏,是本支最重要的一課:交易不等於防併發。四層策略的交易邊界一致,差異只在鎖策略 —— 否則比較的就不是鎖,而是交易。

## Capabilities

### New Capabilities

- `api-ticket-purchase`:購票端點的契約 —— 請求格式、成功與失敗回應、冪等鍵的角色。
- `strategy-no-lock`:第 0 層對照組的驗收 —— 正確性驗收是**刻意失敗**,效能量測項目包含「超賣張數」這個本層特有指標。
- `platform-api-response-format`:統一回應 wrapper 的形狀、`data` 為空時的行為、錯誤碼與 HTTP 狀態的對應規則。此契約會被後續每個 `api-*` 引用,獨立成 spec 避免重複。

### Modified Capabilities

- `platform-hexagonal-layering`:新增「禁止單獨注入 `PurchaseTicketUseCase`」的需求,並更新強制方式表格中該條的納入時機。這是三條延後規則的最後一條。

## Impact

**新增相依:** 無。本支使用既有的 webmvc、validation、data-jpa。

**受影響的既有檔案:** `HexagonalLayeringTest`(補第 8 條規則)、`pom.xml`(surefire 排除 `overselling-evidence` tag)、`openspec/specs/platform-hexagonal-layering/spec.md`(archive 時合併)。

**需要使用者手動執行:** 無。超賣證據測試以 Testcontainers 執行,不碰共用資料庫 —— 1000 執行緒的併發測試若打到 `~/dev-databases`,會耗盡它預設 100 的連線上限並波及其他專案。
