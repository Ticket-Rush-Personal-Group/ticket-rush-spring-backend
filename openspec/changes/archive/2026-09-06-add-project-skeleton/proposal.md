## Why

專案目前只有文件,沒有任何可執行的程式碼。後續七支 change 都要建立在一個能編譯、能測試、且**架構約束已由機器守門**的骨架上。

先建骨架而非先寫功能,關鍵理由是順序:**ArchUnit guardrail 必須先於被它守護的程式碼存在**。若等到第 2 支寫完 entity 才補規則,那批 entity 就是在無人看管的情況下寫出來的,而「domain 零依賴」正是本專案「四種策略切換、domain 零改動」這項主張的技術基礎。

**怎樣算做完:** `./mvnw verify` 綠,且每條 ArchUnit 規則都經過反向驗證 —— 造一個違規類別確認它真的變紅。此時 repo 內沒有任何 domain 類別,規則掃不到東西也會綠,反向驗證是唯一的正確性依據。

## What Changes

- 新增 Maven 專案:`pom.xml`(**Spring Boot 4.1.1** / Java 21)與 **Maven Wrapper**
- 新增六角分層的 package 骨架(`domain` / `application` / `adapter` / `infrastructure`)
- 新增 Spotless 格式化設定,納入驗證鏈
- 新增 ArchUnit 架構守則:五條架構約束中可自動化的四條
- 新增 Spring Boot 啟動類別與 actuator 健康檢查端點
- 新增最小 `application.yml`

**本支刻意不含資料庫。** 沒有 JPA、Flyway、PostgreSQL driver、Testcontainers、Redis —— 那些屬於第 2 支(`add-domain-model`)與第 8 支(`add-redis-prededuct-strategy`)。相依套件跟著使用它的 change 進來,不預先塞進 `pom.xml`。

## Capabilities

### New Capabilities

- `platform-hexagonal-layering`:六角分層的架構約束,以及**每條約束靠什麼強制**(ArchUnit 測試 / 編譯 / 自律)。這支 spec 是後續七支 change 的地基 —— 它定義了什麼樣的程式碼會被擋下來。

## Impact

**新增檔案:** `pom.xml`、`mvnw` / `mvnw.cmd` / `.mvn/`、`src/main/java/com/alantsai/ticketrush/`(啟動類別 + 分層 package)、`src/main/resources/application.yml`、`src/test/java/com/alantsai/ticketrush/architecture/`。

**新增相依:** `spring-boot-starter-webmvc`、`spring-boot-starter-validation`、`spring-boot-starter-actuator`、各 starter 對應的 `-test`、`archunit-junit5`、`spotless-maven-plugin`。

**注意 Boot 4 的命名變化**:`spring-boot-starter-web` 已更名為 `spring-boot-starter-webmvc`;單一的 `spring-boot-starter-test` 已拆分為各 starter 對應的 `-test`(`-webmvc-test` / `-validation-test` / `-actuator-test`)。網路上 3.x 的相依寫法在此不適用。

**需要使用者手動執行:** 無。本支不連任何外部服務,`./mvnw verify` 在乾淨的機器上就能跑完。

**一個前置限制:** 本機目前**沒有安裝 Maven**(`mvn` 不在 PATH,只有 `openjdk@21`)。因此 Maven Wrapper 不是可選項而是必要條件 —— 沒有它,連第一次建置都跑不起來。骨架取得方式見 design。
