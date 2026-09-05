## ADDED Requirements

### Requirement: 禁止單獨注入 PurchaseTicketUseCase

`PurchaseTicketUseCase` MUST NOT 被單獨注入。取用途徑 MUST 為 `PurchaseFacade` 持有的 `Map<String, PurchaseTicketUseCase>`。

同一介面存在多個實作時,單一注入點會在 Spring context 啟動階段拋出 `NoUniqueBeanDefinitionException`。本規則的價值不在於避免那個錯誤 —— 它本來就會炸 —— 而在於**防止有人以 `@Qualifier` 或 `@Primary` 繞過它**:那會讓某個 controller 或 service 直接綁定特定策略,策略便不再可自由抽換,「同一個 API、四種實作」這項前提就被破壞了,而且不會有任何錯誤訊息。

策略的選擇 MUST 封裝於 facade 之內,MUST NOT 洩漏至 adapter 層。

#### Scenario: 於 application service 單獨注入

- **WHEN** `application.service` 的類別以建構子或欄位注入 `PurchaseTicketUseCase`
- **THEN** ArchUnit 測試 SHALL 失敗

#### Scenario: 於 web adapter 單獨注入

- **WHEN** `adapter.in.web` 的 controller 注入 `PurchaseTicketUseCase`
- **THEN** ArchUnit 測試 SHALL 失敗 —— controller 只能依賴 `PurchaseFacade`

#### Scenario: 以 Qualifier 指定特定策略

- **WHEN** 任何類別以 `@Qualifier` 注入特定的 `PurchaseTicketUseCase` 實作
- **THEN** ArchUnit 測試 SHALL 失敗 —— 這正是本規則要防的繞過方式

#### Scenario: facade 持有策略集合

- **WHEN** `PurchaseFacade` 注入 `Map<String, PurchaseTicketUseCase>`
- **THEN** ArchUnit 測試 SHALL 通過 —— 集合注入是唯一允許的取用方式

## MODIFIED Requirements

### Requirement: 架構約束必須標明強制方式

每條架構約束 MUST 標明其強制方式。無機器把關的約束 MUST 明確標示為〔自律〕,不得表述得像有自動檢查。

誤以為有守門的約束,實際風險高於已知沒有守門的約束 —— 後者至少會被人留意。

本專案架構約束的現況:

| 約束 | 強制方式 | 納入時機 |
| --- | --- | --- |
| 分層依賴方向 | ArchUnit | 第 1 支 |
| domain 無框架依賴 | ArchUnit | 第 1 支 |
| 禁用 `@CrossOrigin` | ArchUnit | 第 1 支 |
| `@Transactional` 位置 | ArchUnit | 第 2 支(已納入) |
| JPA entity 不外洩 persistence | ArchUnit | 第 2 支(已納入) |
| 禁止單獨注入 `PurchaseTicketUseCase` | ArchUnit | **第 3 支(已納入)** |
| `@Transactional` self-invocation 失效 | **〔自律〕** | 永遠 —— 見下方 scenario |

**延後的規則至此全數到期。** 三條規則各自等到「被守護的對象存在」才納入,期間的空窗由文件與本表格記錄 —— 沒有任何一條是被遺忘的。

#### Scenario: 約束無法自動化

- **WHEN** 某條約束無法以 ArchUnit 或編譯期檢查涵蓋(如 self-invocation:它取決於呼叫是否經過 Spring AOP proxy,屬執行期語意而非靜態依賴關係)
- **THEN** MUST 在 `CLAUDE.md` 的 Hard Rules 標為〔自律〕,並說明為何無法自動化

#### Scenario: 約束的對象尚未存在

- **WHEN** 某條約束指涉的類別、**或規則引用的註解類別本身**,在當前 change 尚未出現於 classpath
- **THEN** 該規則 MUST 延後至對象出現的那支 change,不得預先加入 —— 無法反向驗證的規則不納入

#### Scenario: 規則沒有掃描到任何對象

- **WHEN** ArchUnit 規則的 `that()` 過濾後為空集合
- **THEN** ArchUnit 的 `failOnEmptyShould`(1.x 預設為 `true`)MUST 保持開啟,讓規則失敗而非顯示綠燈
- **AND** 若該空集合是已知且暫時的狀態,MUST 以 `allowEmptyShould(true)` 明確標示並註明何時會生效,不得改用全域的 `archRule.failOnEmptyShould=false` —— 那會關閉整個專案的防空轉機制
