# Lessons

> 累積自實際踩到的坑與被糾正的地方。只放真正的坑。

## 撰寫格式

- 短規則用一行 bullet。
- 需要三行以上的,用三段式(**踩到什麼 / Why / How to apply**),放在有日期的 `###` 標題下。
- **三種東西不該進來:** 官方文件的複述(刪)、專案慣例與架構決策(移到 `openspec/project/` —— 先移再刪,不要丟失資訊)、已經有守則自動擋下的(刪 —— 機器抓得到就不需要人記)。
- 定期修剪。沒修剪的 lessons 會變成沒人讀的雜訊。

## 本專案特有

這是學習專案。除了坑之外,也記 **「我原本以為 X,實際是 Y」** —— 那是學習軌跡的證據,不是失誤紀錄。Java 的交易語意、鎖行為、JVM 在容器裡的資源感知,都是容易產生錯誤直覺的地方。

---

### 2026-09-06 — Spring Boot 4 的版本字串與相依命名

**踩到什麼:**

1. Initializr metadata 的 `bootVersion` id 是 `4.1.1.RELEASE`,直接拿它當 `curl -d bootVersion=` 的值,產出的 `pom.xml` parent version 就是 `4.1.1.RELEASE`,而 Maven Central 上只有 `4.1.1`。第一次 `./mvnw compile` 直接死在 `Non-resolvable parent POM`,而錯誤訊息指向「找不到 artifact」,不會告訴你是後綴多餘。
2. 原本以為相依是 `spring-boot-starter-web` 與 `spring-boot-starter-test`(3.x 的寫法),**實際上** Boot 4 把 web 改名為 `spring-boot-starter-webmvc`,並把單一的 `-test` 拆成各 starter 對應的 `-webmvc-test` / `-validation-test` / `-actuator-test`。
3. `@AutoConfigureMockMvc` 也搬家了:`org.springframework.boot.test.autoconfigure.web.servlet` → **`org.springframework.boot.webmvc.test.autoconfigure`**(位於 `spring-boot-webmvc-test` jar)。編譯錯誤訊息是日文(JVM locale),而且只說「package 不存在」,不會告訴你新位置在哪。

**Why:** Spring 自 2.x 之後發布的 artifact 就不帶 `.RELEASE` 後綴,但 Initializr metadata 的 id 仍保留該格式,兩者不一致而沒有任何一方會警告。starter 拆分則是 Boot 4 的設計變更 —— 測試相依改為隨各 starter 模組化。

**How to apply:** 用 Initializr 產生骨架後,**先確認 parent version 確實存在於 Maven Central,再跑第一次建置**(`curl -s https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-starter-parent/maven-metadata.xml`)。查 Boot 4 的相依名稱一律以官方文件為準 —— 網路上絕大多數答案是 3.x,套用後得到的是「找不到 artifact」,而不是明確的版本不符訊息。**遇到「package 不存在」時,直接去 `~/.m2` 的 jar 裡找該 class 的實際位置**(`unzip -l <jar> | grep <ClassName>.class`),比查文件快也比查部落格準。

---

### 2026-09-06 — 反向驗證的兩個陷阱

**踩到什麼:**

1. 驗證 actuator health 測試時,原本打算「刪掉 `exposure.include` 設定」來讓端點消失。**但 Spring Boot 的預設值就是 `health`** —— 刪掉之後測試依然全綠。若沒察覺,會誤以為反向驗證通過。改成 `include: info` 才真的讓端點回 404。
2. 驗證腳本用 `grep "ActuatorHealthTest"` 判斷測試是否失敗,**但那個字串在正常執行的 INFO 日誌裡也會出現**,於是腳本回報「✓ 變紅」而實際上完全沒驗到。改成比對 `^\[ERROR\].*ActuatorHealthTest` 才拿到真實結果。

**Why:** 反向驗證的前提是「改壞的動作真的改壞了」與「檢查真的檢查得到」。這兩件事都可能無聲失敗,而失敗的樣子跟成功一模一樣。

**How to apply:** 改壞時要確認**改的那個值與預設值不同**;檢查失敗時要比對錯誤層級的輸出(`[ERROR]`)或 `Failures: [1-9]`,不要比對測試類別名稱 —— 它在成功時也會出現。**驗證腳本本身也需要被驗證**:一個永遠回報成功的檢查,跟沒有檢查是一樣的。改壞用的腳本一律加 `assert old in s`,沒改到就讓腳本失敗。

---

### 2026-09-06 — Testcontainers 2.x 的 API 與容器共用

**踩到什麼:**

1. `org.testcontainers.postgresql.PostgreSQLContainer`(2.x 的新 package)**不是泛型類別**。1.x 在 `org.testcontainers.containers` 底下是自遞迴泛型 `PostgreSQLContainer<SELF>`,沿用 `new PostgreSQLContainer<>(...)` 會編譯失敗,訊息是「非泛型類別不能使用 `<>`」。兩個 package 在 jar 裡同時存在,IDE 自動 import 可能挑到任一個。
2. 容器在 `@Bean` 方法內 `new`,結果**啟動了兩次**。原以為「一個測試類別一個容器」,實際上 Spring 的 test context 快取是**按設定組合分組**的:`TicketRushApplicationTests` 與 `ActuatorHealthTest` 只差一個 `@AutoConfigureMockMvc`,就成了兩個 context,各自建立一個容器。

**Why:** context 快取的分組依據是註解與設定的組合,不是類別數量。只要有測試類別的設定組合不同,`@Bean` 就會被呼叫多次。

**How to apply:** 容器以 `private static final` 欄位持有,`@Bean` 方法只回傳該實例 —— static 初始化每個 JVM 只執行一次,不受 context 數量影響。**驗證方式:`./mvnw verify 2>&1 | grep -c "Creating container for image"`,數字應為 1。** 改成 static 後 `ActuatorHealthTest` 的耗時從 1.65s 降到 0.28s。

---

### 2026-09-06 — `synchronized` 救不了 `@Transactional` 方法

**踩到什麼:** 為了反向驗證超賣測試,在 `@Transactional` 的 `purchase` 方法上加 `synchronized`,預期超賣會消失。**實際上仍然超賣 47 張**,測試沒有變紅。

**Why:** **鎖的範圍比交易的範圍小。** `@Transactional` 由 AOP proxy 實作 —— proxy 先開交易、再呼叫方法、方法返回後才提交。`synchronized` 只涵蓋方法本體,執行緒 A 離開同步區塊時交易**尚未提交**,執行緒 B 立刻進入並讀到舊值。鎖釋放了,但資料還沒可見。

**How to apply:** 需要互斥時,鎖必須在交易之外(或改用資料庫層級的鎖:`SELECT ... FOR UPDATE`、條件式 UPDATE、提高隔離級別)。**任何「在 service 方法加 synchronized 就安全了」的說法都是錯的**,而它錯得很安靜 —— 併發量小的時候完全看不出來。

實測對照(1000 併發搶 500 張):無鎖超賣 865 張;加 `synchronized` 仍超賣 47 張;改 `SERIALIZABLE` 隔離級別超賣 0,但 822/1000 的請求被拒。

---

### 2026-09-06 — surefire 的 excludedGroups 寫死就覆蓋不掉

**踩到什麼:** 在 `pom.xml` 的 surefire plugin 內寫死 `<excludedGroups>overselling-evidence</excludedGroups>`,想單獨執行時下 `-DexcludedGroups= -Dgroups=overselling-evidence`,結果是 **`Tests run: 0`**。

**Why:** Maven 中 **pom 的明確設定值優先於命令列的 user property**。`-DexcludedGroups=` 覆蓋不掉 pom 的值,於是 `groups`(只選這個 tag)與 `excludedGroups`(排除這個 tag)同時套用,交集為空。而它不會報錯 —— 只是安靜地跑了 0 個測試。

**How to apply:** 需要被命令列覆蓋的 plugin 參數,一律寫成 `${property.name}` 佔位並在 `<properties>` 給預設值,而不是寫死。`Tests run: 0` 且 `BUILD SUCCESS` 是個危險組合,**它看起來跟「測試通過」很像** —— 執行測試後要確認實際跑了幾個。

---

### 2026-09-06 — `docker compose run` 會靜默替換掉 depends_on 的服務

**踩到什麼:** 以 `VIRTUAL_THREADS=true docker compose up -d --force-recreate app` 重建應用來啟用虛擬執行緒,並確認容器內的環境變數確實是 `true`。接著執行壓測腳本,腳本內的 `docker compose run --rm k6` 跑完後,**應用又變回平台執行緒**。連續兩次,兩組「對照數據」其實都是平台執行緒。

**Why:** `docker compose run` 會依**當前解析到的設定**比對 `depends_on` 的服務,不一致就重建它。壓測腳本執行時環境中沒有 `VIRTUAL_THREADS`,compose 解析成預設的 `false`,於是把正在跑虛擬執行緒的容器替換掉。**沒有任何錯誤、警告或提示。**

**How to apply:** 給 `docker compose run` 加 `--no-deps`,或確保腳本內外的環境變數一致。

**更根本的一課:讓被測系統自己報告它當前的設定。** 這個問題是靠應用啟動時輸出的 `availableProcessors` / heap / 執行緒模型抓到的 —— 若測量條件是由人抄寫 compose 設定,抄到的會是「設定值」而不是「實際值」,而兩者不一致正是最需要被發現的情況。**任何跨組比較的實驗,都該讓被測系統自報條件。**
