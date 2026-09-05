# backend 架構

> 六角分層與 nexus 對照、package 根與專案目錄、套件結構、四層策略為何置於 in port、策略切換機制、domain 與 JPA entity 分離、命名慣例、五條不可違反的架構約束。

> 本檔為 `openspec/project.md` 的一部分,導覽見該檔。
> 執行環境見 `backend-runtime.md`;整體專案 spec 見 Group repo。

---

## 1. 架構風格

採 **六角架構(Ports & Adapters)**,分層命名沿用 `nexus-nest-backend` 的既有結構。

沿用的理由是學習預算的分配:此專案的學習目標是 Java 與併發控制。若同時導入一套陌生的架構風格,兩者會互相干擾。既有的心智模型可直接遷移,注意力保留給真正要學的部分。

### 與 nexus 的對照

| nexus (TS) | 本專案 (Java) | 差異原因 |
|---|---|---|
| `domain/model/` | `domain/model/` | — |
| `domain/value-object/` | `domain/valueobject/` | Java package 名不允許連字號 |
| `application/port/in/` | `application/port/in/` | — |
| `application/port/out/` | `application/port/out/` | — |
| `application/service/` | `application/service/` | — |
| `application/facade/` | `application/facade/` | — |
| `adapter/in/web/` | `adapter/in/web/` | — |
| `adapter/out/persistence/` | `adapter/out/persistence/` | — |
| `infrastructure/prisma/` | `infrastructure/config/` | Java 端以 JPA 取代 Prisma |

---

## 2. package 根與專案目錄

**package 根:`com.alantsai.ticketrush`**

Maven 標準目錄結構(`src/main/java`、`src/test/java` 為 Maven 寫死,不可更名):

```
ticket-rush-spring-backend/
├── pom.xml
├── src/
│   ├── main/
│   │   ├── java/com/alantsai/ticketrush/
│   │   └── resources/
│   │       ├── application.yml
│   │       ├── db/migration/        Flyway migration
│   │       ├── templates/           Thymeleaf 模板
│   │       └── static/
│   └── test/
│       ├── java/com/alantsai/ticketrush/
│       └── resources/
├── k6/                              壓測腳本與結果
└── docs/
```

---

## 3. 套件結構

```
com.alantsai.ticketrush
├── TicketRushApplication.java
│
├── domain/                                  無框架依賴,不含任何 Spring/JPA 註解
│   ├── model/
│   │   ├── Event.java
│   │   ├── Stock.java
│   │   └── Order.java
│   ├── valueobject/
│   │   ├── EventId.java
│   │   ├── OrderId.java
│   │   ├── UserId.java
│   │   ├── Quantity.java
│   │   └── IdempotencyKey.java
│   ├── policy/
│   │   └── PurchaseLimitPolicy.java         限購規則,四策略共用
│   └── exception/
│       ├── InsufficientStockException.java
│       └── PurchaseLimitExceededException.java
│
├── application/
│   ├── port/
│   │   ├── in/
│   │   │   ├── PurchaseTicketUseCase.java
│   │   │   └── PurchaseTicketCommand.java
│   │   └── out/
│   │       ├── LoadStockPort.java                    無鎖
│   │       ├── LoadStockForUpdatePort.java           悲觀鎖
│   │       ├── CompareAndDeductStockPort.java        樂觀鎖
│   │       ├── StockCachePort.java                   Redis
│   │       ├── SaveOrderPort.java
│   │       └── LoadUserPurchasedQuantityPort.java
│   ├── service/
│   │   ├── NoLockPurchaseService.java
│   │   ├── PessimisticPurchaseService.java
│   │   ├── OptimisticPurchaseService.java
│   │   └── RedisPreDeductPurchaseService.java
│   └── facade/
│       ├── PurchaseFacade.java              持有策略 Map,對外唯一入口
│       └── StrategyRegistry.java            持有當前策略名稱(執行期可改)
│
├── adapter/
│   ├── in/
│   │   ├── web/
│   │   │   ├── PurchaseController.java      REST API
│   │   │   ├── admin/                       Thymeleaf 管理介面 Controller
│   │   │   └── dto/                         XxxRequest / XxxResponse
│   │   └── scheduler/
│   │       └── ExpiredOrderReleaseScheduler.java
│   └── out/
│       ├── persistence/
│       │   ├── entity/                      XxxJpaEntity
│       │   ├── repository/                  Spring Data JPA 介面
│       │   ├── mapper/                      domain ⇄ entity 轉換
│       │   └── StockPersistenceAdapter.java
│       └── redis/
│           └── StockCacheAdapter.java
│
└── infrastructure/
    ├── config/
    │   ├── WebConfig.java                   CORS 集中設定
    │   ├── RedisConfig.java
    │   └── OpenApiConfig.java
    └── properties/
```

---

## 4. 核心決策:四層策略置於 in port

**四個併發策略實作為 `PurchaseTicketUseCase` 的四個 application service,而非單一 out port 的四個 adapter。**

### 論據一:流程本身不同,不只是技術不同

第 0 / 1 / 2 層在單一 DB 交易內完成「檢查限購 → 扣庫存 → 建訂單」。

第 3 層的 Redis 扣減不參與 DB 交易(Redis 無交易可參與),訂單為非同步落庫。它改變的是**流程與回應時機**,不是扣庫存的手法。out port 由 application service 呼叫、流程由 service 決定 —— 流程不同就不可能靠替換 adapter 達成。

### 論據二:各策略依賴的 out port 不同

| 策略 | 依賴的 out port |
|---|---|
| 無鎖 | `LoadStockPort` + 一般更新 |
| 悲觀鎖 | `LoadStockForUpdatePort`(`SELECT ... FOR UPDATE`) |
| 樂觀鎖 | `CompareAndDeductStockPort`(條件式更新,回傳影響列數) |
| Redis 預扣 | `StockCachePort`,不直接碰 DB 庫存 |

四者對外部世界的需求本就不同。強行收斂成單一 out port,只會得到一個為了容納全部而失去意義的介面。

### 論據三:交易邊界必須歸各策略所有

Spring 的 `@Transactional` 是 AOP proxy,交易邊界綁定在「某類別的某方法」上 —— 它是架構決策,而非實作細節。

第 0 / 1 / 2 層的 service 需要 `@Transactional`;**第 3 層不需要**。若策略置於 out port,上層 service 會統一開啟交易,第 3 層將被迫套上一個空轉的 DB 交易包住一連串 Redis 操作。

### 重複邏輯的處理

四個 service 會共用限購檢查、訂單組裝、冪等鍵處理。

**以組合注入共用元件,不使用繼承。** 限購規則歸 `domain/policy/PurchaseLimitPolicy`,訂單組裝歸 domain factory,四個 service 各自注入使用。

不採 template method 的原因:第 3 層的流程骨架與前三層本質不同(前三層為「扣庫存 → 建訂單」同步順序,第 3 層為「扣 Redis → 回應 → 非同步落庫」)。以繼承綁定骨架,將導致骨架充斥 hook 與條件分支。

---

## 5. 策略切換機制

Spring 注入 `Map<String, PurchaseTicketUseCase>` 時,會自動填入「bean name → bean 實例」。四個實作以 `@Service("noLock")`、`@Service("pessimistic")` 等命名,由 `PurchaseFacade` 持有此 Map。

```
adapter/in/web/PurchaseController
        ↓  僅依賴 facade,不知道策略的存在
application/facade/PurchaseFacade         持有 Map + StrategyRegistry
        ↓
application/service/{四個實作}
```

**策略選擇被封裝在 facade 內,不洩漏至 adapter 層。**

### 切換的執行期需求

壓測需跑完 8 組配置。策略名稱由 `StrategyRegistry` 以 `volatile` 欄位持有,Phase 1 由設定檔初始化,Phase 2 開放 Thymeleaf 管理介面即時修改 —— 切換策略不需重啟。

**但虛擬執行緒無法於執行期切換。** `spring.threads.virtual.enabled` 為啟動時設定。因此壓測流程為:**啟動兩次(平台執行緒 / 虛擬執行緒),每次啟動內跑完四種策略。**

---

## 6. domain model 與 JPA entity 分離

domain 層的 `Order` 為純 Java 物件,不含任何 JPA 或 Spring 註解。persistence adapter 內另有 `OrderJpaEntity`,兩者以 mapper 轉換。

此舉會增加轉換程式碼,但它是「domain 不依賴基礎設施」得以成立的實際支撐,也是本專案「同一套 domain 規則、四種併發實作、domain 零改動」這項主張的技術基礎。

**domain 層對「使用何種鎖」必須一無所知。**

---

## 7. 命名慣例

### Java 硬性規則(編譯器強制)

- 檔名必須等於 public class 名
- 目錄結構必須等於 package 宣告
- 一個檔案僅能有一個 public class —— 相較 TS,檔案數量將顯著增加
- package 名不得含連字號

### 慣例

| 對象 | 規則 | 範例 |
|---|---|---|
| package | 全小寫,無分隔符 | `valueobject` |
| class / interface | PascalCase | `PurchaseFacade` |
| 方法 / 變數 | camelCase | `deductStock()` |
| 常數 | UPPER_SNAKE | `MAX_TICKETS_PER_USER` |

- **介面不加 `I` 前綴**(該慣例屬 .NET)
- **實作類別避免 `Impl` 後綴** —— 四個策略需靠名稱區分語意,`PessimisticPurchaseService` 優於 `PurchaseUseCaseImpl`
- Spring 後綴:`XxxController` / `XxxService` / `XxxRepository` / `XxxConfig`
- DTO:`XxxRequest` / `XxxResponse` / `XxxCommand`
- JPA 實體:`XxxJpaEntity`(與 domain model 區別)
- Port:`XxxPort`

---

## 8. 架構約束(不可違反)

1. **禁止在任何位置單獨注入 `PurchaseTicketUseCase`。** 同一介面存在四個實作,單一注入將拋出 `NoUniqueBeanDefinitionException`。僅能透過 `PurchaseFacade` 的 Map 取用。
2. **domain 層不得出現框架註解**(Spring / JPA / Jackson)。
3. **`@Transactional` 僅標註於 application service**,不得置於 controller(過早,HTTP 相關處理將被納入交易)或 repository(過晚,無法跨多個 repository 保證原子性)。
4. **注意 self-invocation 失效:** 同類別內部呼叫自身的 `@Transactional` 方法不會經過 proxy,交易不生效。此為 Spring 最常見的錯誤來源。
5. **CORS 集中於 `WebConfig`**,不得於個別 Controller 標註 `@CrossOrigin`。

---

## 9. 決策紀錄

| 決策 | 選擇 | 理由 |
|---|---|---|
| 架構風格 | 六角架構,沿用 nexus 分層命名 | 學習預算保留給 Java 與併發 |
| package 根 | `com.alantsai.ticketrush` | 個人專案常見寫法;後續變更成本高故先行定案 |
| 策略位置 | in port 的四個實作 | 流程不同、依賴的 out port 不同、交易邊界須各自持有 |
| 重複邏輯 | 組合注入,不用繼承 | 第 3 層流程骨架本質不同,繼承會使骨架劣化 |
| 策略切換 | facade 持有 `Map<String, UseCase>` | 選擇邏輯不洩漏至 adapter |
| domain 與 entity | 分離,以 mapper 轉換 | 支撐「domain 零改動」的核心主張 |
