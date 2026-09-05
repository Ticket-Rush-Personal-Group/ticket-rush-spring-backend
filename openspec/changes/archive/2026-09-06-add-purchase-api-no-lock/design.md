## Context

前兩支的產出是一個能編譯、有 guardrail、有資料表但沒有任何業務行為的系統。本支打通第一條完整路徑,並產出無鎖對照組的超賣證據。

六角分層、四層策略置於 in port、facade 持有策略 Map、交易邊界歸各策略等既有決策見 `openspec/project/backend-architecture.md`,此處不重述,只寫本支特有的決定。

## Goals / Non-Goals

**Goals:**

- 購票的完整路徑:HTTP → facade → use case → out port → PostgreSQL
- 第 0 層無鎖實作,以及可重現的超賣證據
- 統一 API wrapper 與領域例外到 HTTP 的映射
- 補上最後一條延後的 ArchUnit 規則

**Non-Goals:**

- **不做限購政策** —— 第 5 支
- **不做其他三層策略** —— 第 6、7、8 支
- **不做壓測 harness** —— 第 4 支。本支的超賣證據以 JUnit 併發測試取得,那是**正確性**證據;效能數據要等 k6 與資源受限的容器環境才有比較意義
- **不做認證** —— `userId` 由 request header 帶入

## Decisions

### D1. 第 0 層有 `@Transactional`,但沒有任何鎖

這是本支最重要的決定,也是最容易被誤解的一點。

**為什麼要有交易:** 四層策略的差異必須只在鎖策略。若第 0 層沒有交易而其他層有,比較出來的就不是「鎖的效果」,而是「交易的效果」混雜其中。此外,沒有交易時「庫存已扣但訂單建立失敗」會產生資料不一致,那是另一個問題,會混淆超賣的示範。

**為什麼還是會超賣:** 交易保證的是原子性與隔離級別下的一致性,PostgreSQL 預設的 READ COMMITTED **不會**阻止兩個交易讀到相同的 `available` 後各自寫回。

```
交易 A 讀到 available = 500 → 計算 500 - 1 = 499 → 寫回 499 → commit
交易 B 讀到 available = 500 → 計算 500 - 1 = 499 → 寫回 499 → commit
結果:庫存 499,但兩筆訂單都成立 —— 賣出 2 張
```

**這正是本專案要證明的第一件事:`@Transactional` 不等於併發安全。** 這個誤解在實務上極為普遍,而第 0 層是它的反例。

**不選「第 0 層完全不開交易」**:那會讓超賣與資料不一致兩個問題同時出現,證據就不乾淨了 —— 讀者無法分辨賣超的原因是缺鎖還是缺交易。

### D2. 無鎖策略以「讀取 → 計算 → 寫回」實作,不用 `available = available - ?`

```java
Stock stock = loadStockPort.loadStock(eventId).orElseThrow(...);
Stock deducted = stock.deduct(quantity);   // 領域規則在此檢查
updateStockPort.updateStock(deducted);     // 寫回計算好的絕對值 ← lost update 發生處
```

`UpdateStockPort.updateStock(Stock)` 接收的是**算好的絕對值**,而非增量。這是 lost update 的必要條件,也是第 0 層與第 1、2 層的根本差異。

**這也解釋了為什麼 `CHECK (available >= 0)` 攔不到它** —— 寫回的永遠是非負值。

**不選「由資料庫端計算」**(`UPDATE stock SET available = available - ?`):那個寫法在單一 SQL 內完成讀改寫,PostgreSQL 的列鎖會讓它天然序列化,根本不會超賣 —— 那就不是無鎖對照組了。

### D3. 超賣測試用 `@Tag` 隔離,不用 `@Disabled`

以 `@Tag("overselling-evidence")` 標記,surefire 預設排除,可用 `-Dgroups=overselling-evidence` 單獨執行。

**不選 `@Disabled`**:它在測試報告中顯示為 skipped,外觀等同「寫壞了還沒修」或「暫時關掉」。這個測試是**證據**,不是待修項 —— 幾個月後很可能有人把它「順手修好」,而那正好會消滅本專案的開場數據。獨立 tag 讓意圖寫在名字裡。

### D4. 統一 wrapper 以 `@RestControllerAdvice` 與泛型包裝類別實作

成功回應由 `ApiResponse<T>` 包裝,失敗由 `@RestControllerAdvice` 統一轉換。

領域例外到 HTTP 的映射:

| 例外 | HTTP | code |
| --- | --- | --- |
| `InsufficientStockException` | 409 | `INSUFFICIENT_STOCK` |
| 場次不存在 | 404 | `EVENT_NOT_FOUND` |
| 請求格式錯誤(Bean Validation) | 400 | `INVALID_REQUEST` |
| 冪等鍵重複 | 409 | `DUPLICATE_REQUEST` |

**錯誤碼是領域概念,不是 HTTP 狀態的複述** —— `INSUFFICIENT_STOCK` 說明了發生什麼事,`CONFLICT` 沒有。

### D5. `PurchaseFacade` 現在就建,即使只有一個策略

`Map<String, PurchaseTicketUseCase>` 由 Spring 注入(bean name → 實例),`StrategyRegistry` 持有當前策略名稱(`volatile` 欄位,執行期可改)。

**不選「先直接注入唯一的實作,之後再重構」**:第 4 支起每加一個策略就要改一次 controller 與接線,而重構的每一次都是引入錯誤的機會。更關鍵的是,**「禁止單獨注入 `PurchaseTicketUseCase`」這條守則要在本支生效** —— 若現在採用單獨注入,守則就得等到重構之後,而那正是三支延後規則教過的錯誤。

本支的 `StrategyRegistry` 預設值為 `noLock`,設定來源為 `application.yml`;Phase 2 的管理介面才會開放執行期修改。

### D6. 併發證據測試的規模與斷言

1000 執行緒、庫存 500、每人 1 張。斷言三件事:

1. **累計成功訂單張數 > 500** —— 超賣確實發生
2. **`stock.available` 最終 ≥ 0** —— 成因是 lost update,不是扣成負數
3. **成功訂單數 ≠ 最終庫存減少量** —— 兩者的差額就是超賣張數

第 3 點是最精確的表述:超賣的定義是「賣出量與庫存減少量不一致」。

**執行緒必須真正同時起跑**:以 `CountDownLatch` 對齊,並用虛擬執行緒(`Executors.newVirtualThreadPerTaskExecutor()`)—— 1000 個平台執行緒在測試環境會耗盡資源,而虛擬執行緒正好是本專案後續要量測的維度之一。

## Risks / Trade-offs

- **[超賣的張數每次執行都不同]** → 這是併發的本質。斷言用「>」與「不一致」而非固定數字;README 引用的具體數字需標註測量條件。
- **[1000 執行緒的併發測試耗時較長]** → 以 tag 隔離,不進入預設的 `verify`;需要證據時單獨執行。
- **[`@Tag` 排除後,測試可能長期無人執行而腐化]** → 收尾塊要求實際執行一次並記錄輸出;第 4 支的壓測 harness 也會涵蓋同一情境。
- **[錯誤碼與 HTTP 狀態的對應日後可能調整]** → 對應關係寫入 `platform-api-response-format` spec,變更需經 delta,不會靜默漂移。

## Migration Plan

無 schema 變更 —— 三張表已於第 2 支建立,本支只新增讀寫路徑。

## Open Questions

無。
