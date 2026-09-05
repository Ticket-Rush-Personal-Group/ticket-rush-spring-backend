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

**How to apply:** 改壞時要確認**改的那個值與預設值不同**;檢查失敗時要比對錯誤層級的輸出(`[ERROR]`)或 `Failures: [1-9]`,不要比對測試類別名稱 —— 它在成功時也會出現。**驗證腳本本身也需要被驗證**:一個永遠回報成功的檢查,跟沒有檢查是一樣的。
