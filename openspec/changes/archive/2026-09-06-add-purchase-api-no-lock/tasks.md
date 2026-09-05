> 每一塊(`##` 標題)須能獨立通過驗證鏈:
> `./mvnw compile && ./mvnw spotless:check && ./mvnw verify`
> **本支動到持久化(新增 `UpdateStockPort`),`verify` 是必要的。**
> 超賣證據測試以 `@Tag("overselling-evidence")` 排除於預設建置之外,
> 需以 `./mvnw test -Dgroups=overselling-evidence` 單獨執行。
> 每塊綠燈才進下一塊。**不逐塊 commit** —— 一支 change 只有一個 commit,收尾塊(塊 6)才給指令。
>
> 塊的依賴關係:
> - 塊 1 → 2 → 3 為一條鏈,必須依序:service 要有 port 才能寫,facade 要有 service 才能持有,
>   controller 要有 facade 才能呼叫。
> - **塊 4(超賣證據)依賴塊 3** —— 它打的是完整的 HTTP 路徑,不是直接呼叫 service。
>   這是刻意的:證據要來自使用者實際會走的路徑,否則說服力打折。
> - 塊 5(ArchUnit)依賴塊 2 —— 需要 facade 的集合注入作為「合規樣本」,
>   否則規則只能驗違規、驗不了正常情況不會誤報。

## 1. in port、out port 與無鎖 application service

- [x] 1.1 in port:`PurchaseTicketUseCase`、`PurchaseTicketCommand`、`PurchaseResult`。
      command 的欄位皆為 value object,因此**能被建構出來的 command 必然格式合法** ——
      service 不需要再檢查張數是否為正。`PurchaseResult` 不含任何策略識別資訊
- [x] 1.2 out port:`UpdateStockPort`。**簽章接收算好的 `Stock` 絕對值而非增量**(D2)。
      Javadoc 寫明它是無鎖策略專用,且不得改為資料庫端計算
- [x] 1.3 `StockPersistenceAdapter` 實作 `UpdateStockPort`(現同時實作兩個 port)
- [x] 1.4 `NoLockPurchaseService`,bean name `noLock`,標註 `@Transactional`(D1)。
      另新增 `EventNotFoundException` 與 `ClockConfiguration` ——
      時間由注入的 `Clock` 提供而非 `Instant.now()`,讓建立時間在測試中可控
- [x] 1.5 單元測試 `NoLockPurchaseServiceTest`(5 項,Mockito mock port,不啟動 Spring)。
      庫存不足與場次不存在的案例都額外 `verify(..., never())` 確認**沒有寫回庫存也沒有建單** ——
      只斷言「有拋例外」會漏掉「拋了例外但副作用已發生」這種更糟的情況
- [x] 1.6 **反向驗證**:把 `stock.deduct(quantity)` 改為 `stock`(不扣減),
      `rejectsWhenStockInsufficient` 與 `deductsStockAndCreatesOrder` **兩項同時變紅**,還原後回綠。
      本層沒有自己的「庫存不足」判斷 —— 它委派給 domain 的 `Stock.deduct`,
      這個反向驗證證明了委派確實有效且例外沒有被吞掉
- [x] 1.7 **驗證**:`./mvnw verify` 綠(42 個測試)

## 2. PurchaseFacade 與策略選擇

- [x] 2.1 `StrategyRegistry`:`volatile` 欄位持有當前策略名稱,預設值由 `application.yml` 的
      `ticket-rush.strategy` 提供
- [x] 2.2 `PurchaseFacade`:注入 `Map<String, PurchaseTicketUseCase>`,依 registry 選用。
      另新增 `UnknownStrategyException`,**訊息中列出所有可用的策略名稱** ——
      設定打錯字時,「noLock2 不存在」遠不如「noLock2 不存在,可用的有 [noLock]」有用
- [x] 2.3 單元測試 `PurchaseFacadeTest`(4 項):依 registry 選到正確實作、切換後改變委派對象、
      不存在的策略拋出含可用清單的例外、`switchTo` 立即生效。
      每項都額外 `verify(其他策略, never())` —— 只確認「有呼叫對的」會漏掉「同時也呼叫了錯的」
- [x] 2.4 **反向驗證** —— **原定做法不可行,已調整**。原本寫「把 registry 的預設值改成
      不存在的策略名稱」,但 2.3 是單元測試,`StrategyRegistry` 由測試自行建構,
      根本不讀 `application.yml`,改設定不會影響它。
      **改為移除 `PurchaseFacade` 的 null 檢查**,確認
      `unknownStrategyThrowsWithAvailableNames` 變紅(退化為 `NullPointerException`)。還原後回綠
- [x] 2.5 **驗證**:`./mvnw verify` 綠(46 個測試)

## 3. web adapter、統一 wrapper 與錯誤處理

- [x] 3.1 `ApiResponse<T>` 以 `@JsonInclude(NON_NULL)` 達成「`data` 為 null 時整個 key 不存在」
- [x] 3.2 `ApiErrorResponse`:失敗 wrapper,不含 `data`。另新增 `ErrorCode` enum
- [x] 3.3 `GlobalExceptionHandler`:領域例外 → HTTP 的映射。
      **`DataIntegrityViolationException` 以約束名稱區分**冪等鍵重複與其他情況 ——
      不加區分一律回 409 DUPLICATE_REQUEST 會誤導,外鍵違反與 CHECK 違反也會走到這個 handler,
      而它們不是「重複請求」。未預期的例外一律 500 + 泛用訊息,詳情只入日誌
- [x] 3.4 `PurchaseController` 與 DTO。controller **只依賴 `PurchaseFacade`**,
      不注入 `PurchaseTicketUseCase`,也沒有 `@Transactional`
- [x] 3.5 `PurchaseControllerIntegrationTest`(8 項),涵蓋 spec 的全部 scenario,
      外加「回應不洩漏當前策略」與「冪等鍵超長」。**失敗案例都額外斷言副作用未發生**
      (庫存未變、訂單未建立)—— 只檢查狀態碼會漏掉「回了錯誤但資料已改」
- [x] 3.6 錯誤回應以 `jsonPath("$.data").doesNotExist()` 斷言,**不是斷言它為 null** ——
      兩者在 JSON 上是不同的東西,而客戶端會據此分辨「沒有回傳內容」與「值為空」
- [x] 3.7 **反向驗證**:把 `InsufficientStockException` 的映射改為 500,
      `insufficientStock` 變紅(`Status expected:<409> but was:<500>`),還原後回綠
- [x] 3.8 **驗證**:`./mvnw verify` 綠(54 個測試)

## 4. 超賣證據(本支的核心產出)

- [x] 4.1 surefire 排除設定。**第一次寫死值,導致單獨執行時 `Tests run: 0`** ——
      Maven 中 pom 的明確設定值優先於命令列的 user property,`-DexcludedGroups=` 覆蓋不掉,
      於是 `groups` 與 `excludedGroups` 同時套用、交集為空。
      改為 `<excludedGroups>${surefire.excludedGroups}</excludedGroups>` + property 預設值,
      單獨執行指令為 `./mvnw test -Dsurefire.excludedGroups= -Dgroups=overselling-evidence`
- [x] 4.2 `OversellingEvidenceTest`,`@Tag("overselling-evidence")`,未使用 `@Disabled`
- [x] 4.3 1000 併發經由**真實 HTTP**(`RANDOM_PORT` + JDK `HttpClient`)發出,
      `CountDownLatch` 對齊起跑點,虛擬執行緒發送。
      埠號以 `@Value("${local.server.port}")` 取得而非 `@LocalServerPort` ——
      屬性名稱不會隨版本搬家,而 Boot 4 已經搬過三次 package
- [x] 4.4 三項斷言全部通過。**實測結果**:

      ```
      總請求數      : 1000
      成功建立訂單  : 1000 筆(HTTP 201)
      遭拒          : 0 筆
      初始庫存      : 500
      最終庫存      : 365
      庫存實際減少  : 135
      累計售出張數  : 1000
      >>> 超賣張數  : 865
      ```

      **1000 個請求全部成功,庫存卻只減少 135。** 幾乎所有請求都讀到同一個 `available` 值,
      各自算完後寫回相近的數字。最終庫存 365 ≥ 0,確認成因是 lost update 而非扣成負數。
      重跑的數字在 861～868 之間浮動 —— 併發的本質如此,故斷言用「>」與「不相等」而非固定值
- [x] 4.5 **反向驗證。第一次嘗試失敗,而失敗本身揭露了比原設計更重要的知識點。**

      **嘗試一(失敗):在 `purchase` 方法加 `synchronized`。** 預期超賣消失,實際結果是
      售出 547、**仍超賣 47 張**,測試沒有變紅。

      原因:**`synchronized` 的範圍比 `@Transactional` 的範圍小。** `@Transactional` 由 AOP proxy
      實作,交易的提交發生在方法**返回之後**。執行緒 A 離開 synchronized 區塊時交易尚未提交,
      執行緒 B 隨即進入並讀到舊值 —— 鎖釋放了,但資料還沒可見。
      **在 `@Transactional` 方法上加 `synchronized` 不會讓它變安全**,這是一個真實的經典陷阱。

      **嘗試二(成功):把隔離級別改為 `SERIALIZABLE`。** 成功 178 筆、遭拒 822 筆、
      **超賣 0**,測試變紅。還原後超賣 861 重現。

      這證明該測試量的確實是併發下的 lost update。順帶量到一個預告後續的數字:
      提高隔離級別能消除超賣,代價是 **82% 的請求被拒** —— 正確性與吞吐的權衡從這裡就開始了
- [x] 4.6 **驗證**:`./mvnw verify` 綠(54 個測試,超賣測試被排除);
      `./mvnw test -Dsurefire.excludedGroups= -Dgroups=overselling-evidence` 能重現超賣

## 5. 最後一條延後的 ArchUnit 規則

- [x] 5.1 規則以**欄位型別**判定(`noFields().should().haveRawType(...)`)。
      這個判定方式同時涵蓋建構子注入(參數最終賦值給欄位)與 `@Qualifier` 繞過
      (即使指定實作,欄位型別仍是該介面)。
      `PurchaseFacade` 注入的是 `Map<String, PurchaseTicketUseCase>`,欄位型別為 Map,
      因此**不需要為 facade 開例外** —— 規則可對全專案一致套用
- [x] 5.2 **反向驗證,三項全過**:
      - `application/service/ProbeDirectInject` 建構子注入 → **✓ 變紅**
      - `adapter/in/web/ProbeQualifiedController` 以 `@Qualifier("noLock")` 注入 → **✓ 變紅**
      - **防誤報**:移除樣本後 `PurchaseFacade` 的 Map 注入維持綠 → **✓ 不誤報**
      第三項不可跳過:反向驗證通常只驗「違規會紅」,但會誤報的規則同樣會癱瘓開發
- [x] 5.3 更新 `CLAUDE.md` 的 Hard Rules:補上「以 `@Qualifier` 繞過同樣禁止」,
      並說明本規則真正要防的不是啟動失敗
- [x] 5.4 **驗證**:`./mvnw verify` 綠(55 個測試,8 條 ArchUnit 規則)

## 6. 收尾

- [x] 6.1 完整驗證鏈實際輸出:

      ```
      ########## ./mvnw compile ##########
      [INFO] BUILD SUCCESS

      ########## ./mvnw spotless:check ##########
      [INFO] Spotless.Java is keeping 68 files clean - 0 needs changes to be clean
      [INFO] BUILD SUCCESS

      ########## ./mvnw verify ##########
      [INFO] Tests run: 55, Failures: 0, Errors: 0, Skipped: 0
      [INFO] Building jar: target/ticket-rush-0.0.1-SNAPSHOT.jar
      [INFO] BUILD SUCCESS
      ```

- [x] 6.2 **超賣證據(README 的開場數據)**:

      ```
      總請求數      : 1000
      成功建立訂單  : 1000 筆(HTTP 201)
      遭拒          : 0 筆
      初始庫存      : 500
      最終庫存      : 356
      庫存實際減少  : 144
      累計售出張數  : 1000
      >>> 超賣張數  : 856
      ```

      **測量條件**:單機 JVM、嵌入式 Tomcat、Testcontainers `postgres:17`、
      虛擬執行緒發送 1000 併發、**未套用資源限制**、隔離級別為 PostgreSQL 預設的 READ COMMITTED、
      策略為 `noLock` 且帶 `@Transactional`。

      **此為正確性證據,非效能數據。** 多次執行落在 856～868 之間 —— 併發的本質如此。
      README 引用時必須連同測量條件一起呈現,單獨的數字不可比較。
      具備比較意義的效能數據要等第 4 支的 k6 與資源受限容器環境。
- [x] 6.3 更新 `tasks/todo.md`:第 3 支打勾
- [x] 6.4 `tasks/lessons.md` 寫入兩則:
      **(1) `synchronized` 救不了 `@Transactional` 方法** —— 鎖的範圍比交易小,
      實測加上後仍超賣 47 張。這條同時進了 `CLAUDE.md` 的 Hard Rules〔自律〕。
      **(2) surefire 的 `excludedGroups` 寫死就覆蓋不掉** —— `Tests run: 0` 加上
      `BUILD SUCCESS` 看起來很像「測試通過」
- [x] 6.5 需要使用者手動執行的動作:**無**
- [x] 6.6 archive 這支 change(`openspec archive add-purchase-api-no-lock -y`):
      `api-ticket-purchase`、`strategy-no-lock`、`platform-api-response-format` 新建,
      `platform-hexagonal-layering` 更新
