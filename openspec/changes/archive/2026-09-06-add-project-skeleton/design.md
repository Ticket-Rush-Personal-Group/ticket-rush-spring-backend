## Context

repo 內目前只有文件,沒有任何 Java 檔案、沒有 `pom.xml`。

**本機沒有安裝 Maven** —— `mvn` 不在 PATH,只有 Homebrew 的 `openjdk@21`。這使 Maven Wrapper 從「建議做法」變成「唯一可行路徑」:沒有 wrapper,連第一次建置都無法執行。這個限制直接決定了骨架的取得方式(見 D1)。

六角分層、四層策略置於 in port、交易邊界歸各策略、domain 與 JPA entity 分離等既有決策見 `openspec/project/backend-architecture.md`,此處不重述。

## Goals / Non-Goals

**Goals:**

- 一個 `./mvnw verify` 能跑綠的 Maven 專案
- 六角分層的 package 骨架,每層職責以 `package-info.java` 記錄
- ArchUnit 守則,涵蓋此階段可自動化的架構約束,且每條都經反向驗證
- Spotless 納入驗證鏈

**Non-Goals:**

- **不含任何資料庫相依** —— JPA、Flyway、PostgreSQL driver、Testcontainers 全部留給第 2 支
- **不含 Redis** —— 留給第 8 支
- **不含業務端點** —— 除 actuator health 之外沒有任何 controller
- **不含 Dockerfile 與 compose** —— 壓測環境屬於第 4 支
- **不含 Thymeleaf** —— 管理介面是 Phase 2

相依套件跟著使用它的 change 進來。預先把 Phase 1 會用到的全部塞進 `pom.xml`,會讓骨架帶著一批當下無法驗證的設定 —— 例如 Redis starter 一旦加入,應用啟動就會嘗試連線,而此時沒有任何測試會發現它連錯了。

## Decisions

### D1. 骨架由 Spring Initializr 產生,不手寫 `pom.xml`

以 `curl` 呼叫 `start.spring.io` API 取得 zip,解壓後即含 `mvnw`、`mvnw.cmd`、`.mvn/wrapper/`。

**不選「手寫 pom.xml + 手動補 wrapper」**:Maven Wrapper 需要 `maven-wrapper.jar` 與 `maven-wrapper.properties` 版本相互對應,手動湊容易錯版,而錯版的症狀是第一次建置就失敗且訊息不指向真正原因。且本機沒有 `mvn`,無法用官方的 `mvn wrapper:wrapper` 產生。Initializr 是官方來源,且一行指令可重現。

**不選「用瀏覽器到 start.spring.io 下載」**:同樣可行,但無法寫進 tasks 成為可重跑的步驟。

**Spring Boot 版本為 4.1.1。** 撰寫時 Initializr 已無 3.x 可選(最低 `4.0.8`),3 這條線已從 Initializr 下架。原先文件寫的「Spring Boot 3.x」是基於過時認知,已一併更正。

**版本字串有個陷阱,實際踩到過:** Initializr metadata 的 `bootVersion` id 是 `4.1.1.RELEASE`,但 Maven Central 上的 artifact 是 `4.1.1` —— Spring 自 2.x 之後就不再帶 `.RELEASE` 後綴。Initializr 會把 id 原樣填入 `pom.xml` 的 parent version,產出一個**無法解析 parent POM** 的專案。`curl` 時要帶不含後綴的版本號,或在產出後檢查 parent version。

**Boot 4 與 3.x 的兩個命名差異,影響後續每一支 change:**

- `spring-boot-starter-web` → **`spring-boot-starter-webmvc`**
- `spring-boot-starter-test` → 拆分為各 starter 對應的 `-test`(`-webmvc-test` / `-validation-test` / `-actuator-test`)

### D2. ArchUnit 規則只寫「此刻守得住」的四條,其餘跟著對象進來

本支納入:

| 規則 | 內容 |
| --- | --- |
| R1 | 分層依賴方向:`domain` 不依賴任何其他層;`application` 不依賴 `adapter` |
| R2 | `domain` 不得出現 Spring / JPA / Jackson 的 annotation 或 import |
| R4 | 不得使用 `@CrossOrigin`(類別與方法各一條) |

> **實作後修正:R3 也被移出。** 原本規劃納入本支,但 `dependency:tree` 確認 spring-tx 不在
> classpath(相依只有 webmvc / validation / actuator),`@Transactional` 這個註解類別本身不存在,
> 連違規樣本都造不出來。這比下方原本的判準更進一步 —— **不只「被守護的類別」要存在,
> 規則引用的註解本身也要存在**,否則無法反向驗證。R3 併入第 2 支,屆時 `spring-data-jpa`
> 會帶入 spring-tx。

**延後的四條,理由是它們指涉的對象(類別或註解)還不存在:**

- 「`@Transactional` 位置」→ spring-tx 不在 classpath,第 2 支才有
- 「JPA entity 不得外洩 `adapter.out.persistence`」→ 第 2 支才有 entity
- 「禁止單獨注入 `PurchaseTicketUseCase`」→ 該介面在第 3 支才出現
- 「self-invocation 失效」→ **ArchUnit 抓不到**。它需要判斷方法呼叫是否經過 Spring 的 AOP proxy,那是執行期語意,不是靜態依賴關係。這條永遠是〔自律〕

**不選「現在就寫滿五條」**:兩條指涉不存在的類別,規則會恆為綠,而且無法用反向驗證證明它不空轉 —— 造不出違規樣本,因為連合規樣本都沒有。留下一條無法驗證的規則,比沒有規則更危險:它讓人以為有守門。

### D3. package 骨架用 `package-info.java` 佔位,不用 `.gitkeep`

Java 不追蹤空目錄,需要佔位檔。`package-info.java` 同時承擔文件職責 —— 每個 package 的檔案裡寫一段繁體中文 Javadoc,說明該層的職責與依賴限制。

**不選 `.gitkeep`**:它只佔位,沒有其他價值。`package-info.java` 是 Java 的既有機制,且 ArchUnit 的 package 掃描也需要 package 真實存在。

### D4. Spotless 採 `palantir-java-format`

**不選 `google-java-format`**:它使用 2 空格縮排。Java 的型別名稱與泛型宣告本就冗長,2 空格在多層巢狀下辨識層級困難,而 Spring 生態的慣例是 4 空格。palantir 使用 4 空格且對 Java 21 語法(record pattern、switch pattern matching)支援完整。

首次執行 `spotless:apply` 會重寫所有檔案 —— 骨架階段檔案數少,影響可忽略。

### D5. actuator 僅暴露 `health`

`management.endpoints.web.exposure.include` 明確設為 `health`,不使用預設以外的端點。

第 4 支的 compose healthcheck 會打這個端點。**不選「暴露全部端點」**:`env`、`configprops`、`heapdump` 會洩漏設定與記憶體內容,而這個專案之後要 public。

## Risks / Trade-offs

- **[ArchUnit 規則此刻無真實樣本,恐恆綠]** → 反向驗證寫進 tasks 且標為不可跳過:對每條規則造一個違規類別,確認變紅,刪除後確認 `git status` 乾淨。
  **實作後修正:此風險比預期小。** ArchUnit 1.x 的 `failOnEmptyShould` 預設為 `true`,規則沒檢查到任何對象時會直接失敗而非顯示綠燈 —— 首次執行時方法層級的規則正是因此紅的。類別層級的規則則因 `package-info` 本身算一個類別而非空。反向驗證仍然必要(它證明的是規則**抓得到違規**,而非規則**有對象可掃**),但 ArchUnit 已擋掉「完全空轉」這一半的風險。
- **[Initializr 產生的版本與預期不符]** → `curl` 指令明確帶 `bootVersion` 與 `javaVersion=21`;收尾塊記錄實際版本。
- **[palantir formatter 與 IDE 預設格式衝突]** → 驗證鏈跑 `spotless:check`,格式問題會在 commit 前被擋下,不會累積成一次大規模重排。
- **[骨架不含資料庫,無法驗證連線設定]** → 這是刻意的。連線設定的驗收屬於第 2 支,屆時由 Testcontainers 整合測試證明。

## Migration Plan

不適用 —— 新專案,無既有資料或既有部署。

## Open Questions

無。版本號在實作時由 Initializr 決定並記錄,不需事先定案。
