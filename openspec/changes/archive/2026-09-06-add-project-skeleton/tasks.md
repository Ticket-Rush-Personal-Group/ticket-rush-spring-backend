> 每一塊(`##` 標題)須能獨立通過驗證鏈:
> `./mvnw compile && ./mvnw spotless:check && ./mvnw test`
> 本支不含資料庫,**不需要** `./mvnw verify` 的 Testcontainers 部分(但 `verify` 仍應綠)。
> 每塊綠燈才進下一塊。**不逐塊 commit** —— 一支 change 只有一個 commit,收尾塊(塊 5)才給指令。
>
> 塊的依賴關係:
> - **塊 1 是所有後續塊的前提** —— 沒有 `mvnw` 與 `pom.xml`,任何驗證指令都跑不了。
> - 塊 2 依賴塊 1(package 要先存在,塊 3 的規則才有掃描目標)。
> - **塊 3 是本支的核心,不可跳過反向驗證** —— 此時專案內沒有任何 domain / adapter 類別,
>   四條規則都會在沒守住任何東西的情況下顯示綠燈。反向驗證是唯一的正確性依據。
> - 塊 4 只依賴塊 1,與塊 2、3 互相獨立。

## 1. Maven 骨架與 Wrapper

本機沒有 `mvn`,因此 wrapper 必須隨骨架一起取得,不能事後補。

- [x] 1.1 查詢 Spring Initializr 的預設 Boot 版本並記下。
      **實測:預設 `4.1.1.RELEASE`,可選清單最低只到 `4.0.8`,已無任何 3.x。**
      javaVersion 預設 17,可選 26 / 25 / 21 / 17 —— 維持 21(本機安裝的版本)。
      `curl -s https://start.spring.io/metadata/client | python3 -m json.tool | grep -A3 '"bootVersion"' | head -20`
- [x] 1.2 以明確版本產生骨架 zip 到 scratch 目錄(不要直接解到 repo 根目錄)。
      **實際踩到:`bootVersion` 不能用 metadata 的 id。** metadata 給的是 `4.1.1.RELEASE`,
      但 Maven Central 上的 artifact 是 `4.1.1`,Initializr 會把 id 原樣填進 parent version,
      產出的專案第一次建置就死在 `Non-resolvable parent POM`。已修正為 `4.1.1`,詳見 `tasks/lessons.md`。
      ```bash
      curl -s https://start.spring.io/starter.zip \
        -d type=maven-project -d language=java -d javaVersion=21 \
        -d bootVersion=<步驟 1.1 查到的版本> \
        -d groupId=com.alantsai -d artifactId=ticket-rush \
        -d packageName=com.alantsai.ticketrush -d name=ticket-rush \
        -d dependencies=web,validation,actuator \
        -o <scratch>/skeleton.zip
      ```
- [x] 1.3 從 zip 取出並放進 repo(另含 `.gitattributes`,Initializr 有附且有用):`mvnw`、`mvnw.cmd`、`.mvn/`、`pom.xml`、
      `src/main/java/com/alantsai/ticketrush/TicketRushApplication.java`。
      **`HELP.md` 不要**。**`.gitignore` 不要直接覆蓋** —— repo 已有一份,
      把 Initializr 版本裡缺的項目(如 `!**/src/main/**/target/`)合併進去,不要整檔取代。
- [x] 1.4 `application.properties` 改為 `application.yml`(專案慣例用 yml),內容暫時只有 `spring.application.name`
- [x] 1.5 確認 `mvnw` 有可執行權限(`chmod +x mvnw`)。
      **無 jar 需要對版**:wrapper 3.3.4 用 `distributionType=only-script`,
      `.mvn/wrapper/` 只有 properties,Maven 本體(3.9.16)於首次執行時下載。
- [x] 1.6 **驗證**:`./mvnw compile` 綠(首次執行下載了 Maven 3.9.16 與相依)。
      產出 `target/classes/com/alantsai/ticketrush/TicketRushApplication.class`。

## 2. 六角 package 骨架與 Spotless

- [x] 2.1 建立分層 package,每個都放一份 `package-info.java`,內含繁體中文 Javadoc
      (實際建立 14 個,每個的 Javadoc 寫出該層職責與依賴限制)
      說明該層職責與依賴限制:
      `domain.model` / `domain.valueobject` / `domain.policy` / `domain.exception` /
      `application.port.in` / `application.port.out` / `application.service` / `application.facade` /
      `adapter.in.web` / `adapter.in.scheduler` / `adapter.out.persistence` / `adapter.out.redis` /
      `infrastructure.config` / `infrastructure.properties`
- [x] 2.2 `pom.xml` 加入 `spotless-maven-plugin` 3.10.2,formatter 用 `palantir-java-format`(D4)。
      **刻意不綁 `validate` phase** —— 驗證鏈裡 `spotless:check` 是獨立一步,
      綁進 `validate` 會讓每次 `compile` 都被格式問題擋住,開發摩擦大於收益。
- [x] 2.3 執行 `./mvnw spotless:apply` 讓既有檔案符合格式
- [x] 2.4 **驗證**:`./mvnw spotless:check && ./mvnw compile` 綠

## 3. ArchUnit 架構守則(核心塊,反向驗證不可跳過)

- [x] 3.1 `pom.xml` 加入 `archunit-junit5` 1.5.0(test scope)
- [x] 3.2 在 `src/test/java/com/alantsai/ticketrush/architecture/HexagonalLayeringTest.java` 寫規則(D2)。
      **實際只寫了三條,R3 於實作中被移出,理由見 3.3**:
      **R1** 分層依賴方向(`layeredArchitecture()`:domain 不依賴任何層,application 不依賴 adapter)
      **R2** domain 不得有 Spring / JPA / Jackson 的 annotation 或 import
      **R4** 全專案不得使用 `@CrossOrigin`(類別與方法各一條)
      合計 4 個 `@ArchTest`。註解一律用字串 FQN 而非 class 參考,讓守則可先於相依存在。
- [x] 3.3 **不要**寫「禁止單獨注入 `PurchaseTicketUseCase`」與「JPA entity 不外洩」——
      對象尚不存在,無法反向驗證,依 D2 延後至第 3、2 支。
      **實作中追加移出 R3(`@Transactional` 位置守則)**:`./mvnw dependency:tree` 確認
      spring-tx 不在 classpath(只有 spring-context / spring-web / spring-webmvc 7.0.9),
      連違規樣本都造不出來。這比 D2 原本的理解更進一步 —— **不只「被守護的類別」要存在,
      規則引用的註解本身也要存在**,否則無法反向驗證。同樣依 D2 延後至第 2 支
      (`spring-data-jpa` 會帶入 spring-tx)。已在 `HexagonalLayeringTest` 的類別 Javadoc 註明。
- [x] 3.4 **反向驗證(逐條做,不可合併)**。四條 `@ArchTest` 全數確認變紅:
      - R1 `layerDependencyDirection` → `domain/model/ProbeViolation` 持有
        `application/facade/ProbeTarget` 欄位 → **✓ 變紅**
      - R2 `domainIsFrameworkFree` → `domain/model/ProbeViolation` 標註 `@Component` → **✓ 變紅**
      - R4a `noCrossOriginOnClasses` → `adapter/in/web/ProbeController` 類別標註 `@CrossOrigin` → **✓ 變紅**
      - R4b `noCrossOriginOnMethods` → 同檔案改標在方法上 → **✓ 變紅**

      刪除樣本時**必須連 `target/classes` 下對應的 `.class` 一併刪除** —— ArchUnit 掃描的是
      編譯產物而非原始碼,只刪 `.java` 會讓下一次驗證仍抓到已移除的違規。
      還原後 `./mvnw test` 全綠(5 個測試),`find src target -name "Probe*"` 無殘留。

- [x] 3.4b **意外收穫:ArchUnit 自己就有防空轉機制**。首次執行時
      `noTransactionalOnAdapterMethods` 直接失敗,訊息為 `failed to check any classes` ——
      ArchUnit 1.x 的 `failOnEmptyShould` 預設為 `true`,規則沒檢查到任何對象就會紅。
      這推翻了 design 中「四條規則都會空過顯示綠燈」的假設:**類別層級的規則因為
      `package-info` 算一個類別而非空,方法層級的則會被 ArchUnit 擋下。**
      反向驗證的必要性不變,但 ArchUnit 已擋掉一部分風險。
- [x] 3.5 **驗證**:`./mvnw test` 綠(`Tests run: 5, Failures: 0` / `BUILD SUCCESS`),
      且 3.4 的四次反向驗證都確實變紅過

## 4. actuator 與啟動煙霧測試

- [x] 4.1 `application.yml` 設定 `management.endpoints.web.exposure.include: health`(D5),
      不暴露其他端點。
      **發現:這個值與 Spring Boot 的預設值相同**,所以它不改變當下的行為 —— 刪掉設定,
      health 端點依然可用。它的價值在於**明確宣告**:日後有人加入其他端點時,
      這行會成為「只暴露 health」這個決定的可見依據,而不是依賴預設值的沉默行為。
- [x] 4.2 寫一支 `@SpringBootTest` + `MockMvc` 的測試:context 能載入,且 `GET /actuator/health` 回 200 且 status 為 UP。
      **踩到 Boot 4 的第三處搬家**:`@AutoConfigureMockMvc` 已從
      `org.springframework.boot.test.autoconfigure.web.servlet` 移到
      `org.springframework.boot.webmvc.test.autoconfigure`(位於 `spring-boot-webmvc-test` jar)。
      Boot 4 把 test autoconfigure 按技術棧重新分組了。
- [x] 4.3 **反向驗證**:把 `include` 改成 `info`(**不是刪掉設定** —— 預設值就是 `health`,
      刪掉測試依然會綠,那證明不了任何事),確認 health 測試變紅:
      `ActuatorHealthTest.healthEndpointIsUp:35 Status expected:<200> but was:<404>`,再改回來。
      **第一次的驗證腳本判斷錯誤**:用 `grep "ActuatorHealthTest"` 判斷是否失敗,
      但那個字串在正常執行的 INFO 日誌裡也會出現,所以誤報成功。改為比對
      `^\[ERROR\].*ActuatorHealthTest` 才拿到真實結果。
      **驗證腳本本身也需要被驗證** —— 一個永遠回報成功的檢查,跟沒有檢查是一樣的。
- [x] 4.4 **驗證**:`./mvnw test` 綠(`Tests run: 6, Failures: 0` / `BUILD SUCCESS`)
      (全程未執行 `./mvnw spring-boot:run` —— 見 CLAUDE.md Hard Rules)

## 5. 收尾

- [x] 5.1 跑完整驗證鏈,實際輸出:

      ```
      ########## ./mvnw compile ##########
      [INFO] BUILD SUCCESS

      ########## ./mvnw spotless:check ##########
      [INFO] Spotless.Java is keeping 18 files clean - 0 needs changes to be clean,
             0 were already clean, 18 were skipped because caching determined they were already clean
      [INFO] BUILD SUCCESS

      ########## ./mvnw verify ##########
      [INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0 -- in TicketRushApplicationTests
      [INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0 -- in ActuatorHealthTest
      [INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 -- in HexagonalLayeringTest
      [INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
      [INFO] Building jar: target/ticket-rush-0.0.1-SNAPSHOT.jar
      [INFO] BUILD SUCCESS
      ```
- [x] 5.2 **版本基準**(日後排查行為差異的依據):

      | 項目 | 版本 |
      | --- | --- |
      | Spring Boot | 4.1.1 |
      | Spring Framework | 7.0.9(由 Boot 傳遞) |
      | Java | 21 |
      | Maven | 3.9.16(wrapper 3.3.4,`distributionType=only-script`) |
      | spotless-maven-plugin | 3.10.2 |
      | palantir-java-format | 2.80.0(由 spotless 帶入) |
      | archunit-junit5 | 1.5.0 |
- [x] 5.3 更新 `tasks/todo.md`:第 1 支打勾;第 2 支加註「必須補上從本支延後的兩條 ArchUnit 規則」
- [x] 5.4 `tasks/lessons.md`:寫入兩則 —— Boot 4 的版本字串與三處相依搬家、反向驗證的兩個陷阱
      (改壞的值與預設值相同、檢查條件比對到 INFO 日誌)
- [x] 5.5 需要使用者手動執行的動作:**無**。本支不連任何外部服務,乾淨機器上 `./mvnw verify` 即可跑完
- [x] 5.6 archive 這支 change(`openspec archive add-project-skeleton -y`):
      `specs/platform-hexagonal-layering` 合併進 `openspec/specs/`,
      change 資料夾移至 `openspec/changes/archive/2026-09-06-add-project-skeleton/`

<!--
  實作過程中的兩種常見狀況,直接寫在對應項目上,不要另開文件:

  取消的項目 —— 用刪除線 + 粗體結論 + 理由
  中途插入的塊 —— 用 3b 這種編號插在相關塊後面,並交代為什麼被併進來
-->
