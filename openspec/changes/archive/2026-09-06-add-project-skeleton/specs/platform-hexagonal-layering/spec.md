## ADDED Requirements

### Requirement: 六角分層的依賴方向

`domain` 層 MUST NOT 依賴 `application`、`adapter`、`infrastructure` 任一層。`application` 層 MUST NOT 依賴 `adapter` 層。依賴方向只能由外向內。

此約束由 ArchUnit 的 `layeredArchitecture()` 強制,違反時 `./mvnw test` 失敗。

#### Scenario: domain 依賴外層

- **WHEN** `domain` 內的類別 import 了 `application` 或 `adapter` 的類別
- **THEN** ArchUnit 測試失敗,建置中斷

#### Scenario: application 依賴 adapter

- **WHEN** `application.service` 內的類別 import 了 `adapter.out.persistence` 的類別
- **THEN** ArchUnit 測試失敗,建置中斷

#### Scenario: adapter 依賴 application(允許的方向)

- **WHEN** `adapter.in.web` 的類別 import 了 `application.facade` 的類別
- **THEN** ArchUnit 測試通過 —— 由外向內是允許的依賴方向

---

### Requirement: domain 層的框架獨立性

`domain` 層 MUST NOT 出現任何框架的 annotation 或 import,包含 Spring、JPA(`jakarta.persistence`)、Jackson。

這不是風格偏好。domain 對「使用何種鎖」一無所知,是本專案「四種併發策略切換、domain 零改動」這項主張唯一的技術基礎;一旦 domain 綁上 JPA,策略就無法在不動 domain 的前提下抽換。

違反時 `./mvnw test` 失敗。

#### Scenario: domain 類別標註 JPA annotation

- **WHEN** `domain.model` 的類別標註 `@Entity` 或 `@Column`
- **THEN** ArchUnit 測試失敗

#### Scenario: domain 類別引入 Spring

- **WHEN** `domain` 內任一類別 import `org.springframework` 開頭的套件
- **THEN** ArchUnit 測試失敗

#### Scenario: 純 Java 的 domain 類別

- **WHEN** `domain.model` 的類別只使用 JDK 型別與同層的 value object
- **THEN** ArchUnit 測試通過

---

### Requirement: 交易邊界的位置

`@Transactional` MUST NOT 出現在 `adapter.in.web` 或 `adapter.out.persistence`。交易邊界只屬於 `application` 層的 service。

置於 controller 會把 HTTP 處理納入交易範圍;置於 repository 則無法跨多個 repository 保證原子性。兩者在本專案都是實質錯誤 —— 四層策略的差異有一半來自交易邊界的位置。

違反時 `./mvnw test` 失敗。

#### Scenario: controller 標註交易

- **WHEN** `adapter.in.web` 的類別或方法標註 `@Transactional`
- **THEN** ArchUnit 測試失敗

#### Scenario: persistence adapter 標註交易

- **WHEN** `adapter.out.persistence` 的類別或方法標註 `@Transactional`
- **THEN** ArchUnit 測試失敗

---

### Requirement: CORS 設定集中

專案 MUST NOT 使用 `@CrossOrigin`。CORS 只在 `infrastructure.config.WebConfig` 以 `WebMvcConfigurer` 設定一次。

前端位於獨立 repo 且以不同 origin 開發,CORS 設定散落各 controller 時,漏設的症狀是特定端點在瀏覽器失敗而在 curl 正常 —— 難以定位。

違反時 `./mvnw test` 失敗。

#### Scenario: controller 標註 CrossOrigin

- **WHEN** 任一類別或方法標註 `@CrossOrigin`
- **THEN** ArchUnit 測試失敗

---

### Requirement: 架構守則必須經過反向驗證

新增的 ArchUnit 規則 MUST 經反向驗證證明其非空轉:刻意造出違規樣本、確認規則變紅、移除樣本、確認工作目錄乾淨。

規則掃不到任何目標時同樣回報通過。在本專案的骨架階段,`domain` 與 `adapter` 內尚無任何類別,**四條規則都會在沒有守住任何東西的情況下顯示綠燈**。此時反向驗證是判斷規則正確性的唯一依據。

一條無法驗證的守則比沒有守則更危險 —— 它讓人誤以為有把關。

#### Scenario: 新增規則但當下無真實樣本

- **WHEN** 新增一條 ArchUnit 規則,而專案內尚無該規則的適用對象
- **THEN** MUST 臨時建立違規類別,確認測試變紅後移除,並確認 `git status` 乾淨

#### Scenario: 規則改壞後仍為綠

- **WHEN** 反向驗證時造出違規樣本,測試仍然通過
- **THEN** 該規則判定為假守則,MUST 重寫

---

### Requirement: 架構約束必須標明強制方式

每條架構約束 MUST 標明其強制方式。無機器把關的約束 MUST 明確標示為〔自律〕,不得表述得像有自動檢查。

誤以為有守門的約束,實際風險高於已知沒有守門的約束 —— 後者至少會被人留意。

本專案架構約束的現況:

| 約束 | 強制方式 | 納入時機 |
| --- | --- | --- |
| 分層依賴方向 | ArchUnit | 本支 |
| domain 無框架依賴 | ArchUnit | 本支 |
| 禁用 `@CrossOrigin` | ArchUnit | 本支 |
| `@Transactional` 位置 | ArchUnit | 第 2 支(spring-tx 進入 classpath 時) |
| JPA entity 不外洩 persistence | ArchUnit | 第 2 支(entity 出現時) |
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
