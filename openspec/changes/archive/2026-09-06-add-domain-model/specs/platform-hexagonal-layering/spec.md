## ADDED Requirements

### Requirement: JPA entity 的可見性邊界

標註 `@Entity` 的型別 MUST NOT 被 `adapter.out.persistence` 以外的任何類別依賴。

判定 MUST 以 `jakarta.persistence.Entity` 註解為準,MUST NOT 以類別名稱結尾判定。命名慣例可被繞過 —— 有人把 entity 取名 `OrderRecord` 就繞過了「以 `JpaEntity` 結尾」的檢查,而註解繞不過。

entity 一旦外洩到 application 或 domain,持久化的細節就跟著擴散:JPA 的延遲載入、`@ManyToOne` 的關聯導覽、entity 生命週期,全部會滲進不該知道它們的層。屆時「四種策略切換、domain 零改動」就不再成立。

#### Scenario: application 層依賴 entity

- **WHEN** `application.service` 的類別依賴標註 `@Entity` 的型別
- **THEN** ArchUnit 測試 SHALL 失敗

#### Scenario: web adapter 依賴 entity

- **WHEN** `adapter.in.web` 的類別直接回傳或接收標註 `@Entity` 的型別
- **THEN** ArchUnit 測試 SHALL 失敗

#### Scenario: persistence 內部使用 entity

- **WHEN** `adapter.out.persistence` 內的 mapper 與 repository 使用 entity
- **THEN** ArchUnit 測試 SHALL 通過 —— entity 在其所屬的層內是正常的

#### Scenario: 以非慣例名稱命名的 entity

- **WHEN** 某個標註 `@Entity` 的類別未以 `JpaEntity` 結尾,且被外層依賴
- **THEN** ArchUnit 測試 SHALL 仍然失敗 —— 判定依據是註解而非名稱

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
| `@Transactional` 位置 | ArchUnit | **第 2 支(已納入)** |
| JPA entity 不外洩 persistence | ArchUnit | **第 2 支(已納入)** |
| 禁止單獨注入 `PurchaseTicketUseCase` | ArchUnit | 第 3 支(該介面出現時) |
| `@Transactional` self-invocation 失效 | **〔自律〕** | 永遠 —— 見下方 scenario |

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
