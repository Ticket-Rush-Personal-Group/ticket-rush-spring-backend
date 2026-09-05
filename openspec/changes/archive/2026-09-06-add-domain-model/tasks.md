> 每一塊(`##` 標題)須能獨立通過驗證鏈:
> `./mvnw compile && ./mvnw spotless:check && ./mvnw verify`
> **本支動到持久化,`verify`(含 Testcontainers 整合測試)是必要的,不可只跑 `test`。**
> 每塊綠燈才進下一塊。**不逐塊 commit** —— 一支 change 只有一個 commit,收尾塊(塊 6)才給指令。
>
> 塊的依賴關係:
> - **塊 1 不可拆分也不可跳過。** 一旦加入 JPA 相依,`@SpringBootTest` 就會嘗試建立 DataSource,
>   既有的 `ActuatorHealthTest` 會立刻紅。塊 1 必須同時完成相依、資料來源設定與 Testcontainers 接線,
>   否則會留下「編譯過但測試紅」的中間狀態。
> - 塊 2(migration)與塊 3(domain model)互相獨立,可任意順序 —— domain 是純 Java,不碰資料庫。
> - 塊 4 依賴塊 2 與塊 3(要有表也要有 domain 才能做 mapper 與 adapter)。
> - 塊 5 依賴塊 4(entity 存在才能反向驗證 entity 外洩規則;spring-tx 由塊 1 的 JPA 相依帶入)。

## 1. 相依、資料來源與 Testcontainers 接線

- [x] 1.1 `pom.xml` 加入相依。**名稱已用 Initializr 實測確認,不要沿用 3.x 的寫法**:
      `spring-boot-starter-data-jpa`、`spring-boot-starter-flyway`(3.x 是 `flyway-core`)、
      `flyway-database-postgresql`、`postgresql`(runtime)、
      `spring-boot-starter-data-jpa-test`、`spring-boot-starter-flyway-test`、
      `spring-boot-testcontainers`、`testcontainers-junit-jupiter`、`testcontainers-postgresql`
      (後兩者傳統上叫 `junit-jupiter` / `postgresql`)
- [x] 1.2 `application.yml` 加入 datasource,指向共用的 `~/dev-databases`
      (`localhost:5432` / `ticket_rush_db`),密碼由 `${DEV_DB_PASSWORD:}` 帶入,並建立 `.env.example`。
      同時設 `jpa.hibernate.ddl-auto: none`(**塊 2 建立 V1 之後改為 `validate`** ——
      本塊尚無 migration,設 validate 會讓 context 載入失敗)與 `open-in-view: false`
- [x] 1.3 建立共用的 Testcontainers 設定。**實測結果**:`@ServiceConnection` 的 package 沒變
      (`org.springframework.boot.testcontainers.service.connection`),但 **Testcontainers 是 2.0.5**,
      `PostgreSQLContainer` 移到 `org.testcontainers.postgresql` 且**不再是泛型類別** ——
      1.x 的自遞迴泛型 `PostgreSQLContainer<SELF>` 已移除,寫 `<>` 會編譯失敗。
      原 task 內容:`@TestConfiguration` 提供 static
      `PostgreSQLContainer<>("postgres:17")`,以 `@ServiceConnection` 接線(D5)。
      **`@ServiceConnection` 的實際 package 要從 jar 內容確認**
      (`unzip -l <spring-boot-testcontainers jar> | grep ServiceConnection`)——
      前一支的經驗:Boot 4 的 package 位置不能用猜的
- [x] 1.4 既有的 `ActuatorHealthTest` 與 `TicketRushApplicationTests` 以 `@Import` 引入該設定
- [x] 1.4b **容器必須以 static 欄位持有,不能在 `@Bean` 方法內 new**。
      第一次實作時容器**啟動了兩次** —— Spring 的 test context 快取按設定分組,
      有 `@AutoConfigureMockMvc` 與沒有的測試類別屬於不同 context,`@Bean` 於是各建一個容器。
      改為 static 單例後降為 1 次,`ActuatorHealthTest` 的耗時也從 1.65s 降到 0.28s。
      這正是 D5 想避免的成本,但成因跟原本設想的(每個測試類別一個容器)不同 ——
      **是 context 設定的差異造成的,不是測試類別的數量**。
- [x] 1.5 **驗證**:`./mvnw verify` 綠,既有 6 個測試全部維持通過,容器啟動次數為 1

## 2. Flyway migration 與表結構

- [x] 2.1 `src/main/resources/db/migration/V1__init_schema.sql`,建立三張表:
      `event`(id / name / sales_start_at / total_quantity / created_at)、
      `stock`(event_id 為 PK / available / version,含 `CHECK (available >= 0)`)、
      `purchase_order`(id / event_id / user_id / quantity / status / idempotency_key UNIQUE /
      created_at / expires_at)。時間型別一律 `TIMESTAMPTZ`
- [x] 2.2 **表名用 `purchase_order` 不用 `order`**(D1:`order` 是 SQL 保留字)。
      `version` 與 `idempotency_key` 本支即納入,理由見 design 的 Risks。
      另加 `idx_purchase_order_event_user` 索引 —— 第 5 支的限購檢查會以
      `(event_id, user_id)` 聚合查詢,同屬已定案需求。
      `application.yml` 的 `ddl-auto` 由塊 1 的 `none` 改為 `validate`
- [x] 2.3 整合測試 `SchemaMigrationTest`:migration 自動套用,三張表存在,`CHECK (available >= 0)`
      與 `idempotency_key` 唯一約束皆生效。**驗證的是真實 PostgreSQL 上的行為而非 SQL 檔的文字** ——
      約束寫了但沒生效(寫成註解、被後續 migration 覆蓋)是文字比對抓不到的。
      **不用 `@Transactional` 自動回滾**:違反 CHECK 或 UNIQUE 會使 PostgreSQL 交易進入 aborted 狀態,
      同一交易的後續語句全部失敗;改為每個測試後 TRUNCATE 清理
- [x] 2.4 整合測試:以未加引號的原生 SQL 查詢 `purchase_order`,確認不因保留字失敗
- [x] 2.5 **反向驗證**:移除 `CHECK (available >= 0)` 後 `negativeStockIsRejected` 確實變紅
      (`Tests run: 4, Failures: 1` / `BUILD FAILURE`),還原後回到全綠。
      改 migration 不會撞到 Flyway checksum —— Testcontainers 每次都是全新容器,沒有既有的套用紀錄。
      **移除約束的腳本加了 `assert s2 != s`**:正則沒匹配到時要讓腳本失敗,
      否則「沒改壞卻顯示測試綠」會被誤讀成反向驗證通過
- [x] 2.6 **驗證**:`./mvnw verify` 綠(10 個測試)

## 3. domain model 與 value object

- [x] 3.1 value object(record + 緊湊建構子驗證):`EventId`、`OrderId`、`UserId`、
      `Quantity`(必須 > 0)、`IdempotencyKey`(非空、長度 ≤ 64,對應 DB 的 VARCHAR(64))
- [x] 3.2 domain model(不可變 record):`Event`、`Stock`、`Order`、`OrderStatus`。
      `Stock.deduct(Quantity)` 回傳新的 `Stock`,不足時拋 `InsufficientStockException`。
      `Order.id` 是唯一允許為 null 的欄位(尚未持久化),以 `newOrder()` 工廠方法建立
- [x] 3.3 領域例外:`InsufficientStockException`(置於 `domain.exception`,攜帶 eventId / available / requested)
- [x] 3.4 **純單元測試,不啟動 Spring context 也不連資料庫**:`StockTest`(6 項)與
      `ValueObjectValidationTest`(8 項)。**耗時 0.002s 與 0.015s** —— 這個數字本身就是
      「沒有啟動 Spring」的證據,與整合測試的 3.7s 形成對照
- [x] 3.5 **反向驗證**:把 `Stock.deduct` 的判斷從 `available < quantity.value()` 改為
      `available < 0`,確認 `deductBeyondAvailableThrows` 變紅(`Failures: 1`),再還原。
      改壞腳本一樣加了 `assert old in s`,避免「沒改到卻顯示綠」被誤讀成通過
- [x] 3.6 **驗證**:`./mvnw test` 綠(24 個測試)

## 4. JPA entity、mapper、out port 與 persistence adapter

- [x] 4.1 JPA entity:`EventJpaEntity`、`StockJpaEntity`、`OrderJpaEntity`,置於
      `adapter.out.persistence.entity`。
      **兩個實作決定**:(1) `StockJpaEntity.version` **刻意不標註 `@Version`** ——
      標了之後 Hibernate 會對每次 update 做樂觀鎖檢查,會改變第 0 層與第 1 層的行為,
      四層策略的比較就不是同一個基準;樂觀鎖於第 7 支啟用時再決定實作方式。
      (2) `OrderJpaEntity.status` 以 String 而非 domain enum 映射 —— entity 只反映資料庫的形狀,
      列舉轉換集中在 mapper,domain enum 改名時持久化層不受影響
- [x] 4.2 手寫 mapper(D4,不用 MapStruct),置於 `adapter.out.persistence.mapper`
- [x] 4.3 Spring Data JPA repository 介面,置於 `adapter.out.persistence.repository`
- [x] 4.4 out port 介面(D6,**只做這三個**):`LoadEventPort`、`LoadStockPort`、`SaveOrderPort`。
      策略專屬的 port(`LoadStockForUpdatePort` / `CompareAndDeductStockPort` / `StockCachePort`)
      **不在本支定義** —— 它們的形狀取決於各策略的實際需求
- [x] 4.5 persistence adapter 實作上述三個 port(三個 adapter,各對應一個聚合)
- [x] 4.6 整合測試 `PersistenceAdapterIntegrationTest`:port → adapter → JPA → 真實 PostgreSQL。
      **測試對象是 port 介面而非 repository** —— 那才是應用層真正會走的路,包含 mapper 轉換;
      只測 repository 會漏掉 domain 與 entity 之間的轉換錯誤
- [x] 4.7 `JpaEntityMapperTest`(純單元,不需資料庫):三個型別雙向轉換後**整個物件相等**,
      而非只比對部分欄位。另含「未持久化訂單的 null id 不被填成 0」與「狀態列舉以字串往返不失真」
- [x] 4.8 併發測試:**十個**執行緒以相同 `idempotency_key` 同時下單,只有一筆成功。
      以兩道 `CountDownLatch` 對齊起跑點(`ready` 等全部就緒、`start` 同時放行),
      並用 Java 21 的 try-with-resources `ExecutorService` 等待全部終止
- [x] 4.9 **反向驗證,兩項皆通過**:
      - 移除 `uq_purchase_order_idempotency_key` → 併發測試變紅,**`expected: 1 but was: 10`**
        (十個執行緒全部寫入成功)。這個數字證明測試真的在驗約束,而不是碰巧通過
      - 把 mapper 的 `Stock.version` 對應改成常數 `0L` → `stockRoundTripPreservesAllFields` 變紅
      兩項各自還原後回到全綠
- [x] 4.10 **驗證**:`./mvnw verify` 綠(34 個測試)

## 5. 補上兩條延後的 ArchUnit 規則

- [x] 5.1 `@Transactional` 位置守則(類別與方法層級各一條)。第 1 支因 spring-tx 不在 classpath 而延後,
      本支的 `spring-boot-starter-data-jpa` 已帶入
- [x] 5.2 JPA entity 可見性邊界:標註 `jakarta.persistence.Entity` 的型別不得被
      `adapter.out.persistence` 以外的類別依賴。**以註解判定,不以類別名稱結尾判定**(D7)
- [x] 5.3 ~~移除 `noTransactionalOnAdapterMethods` 上的 `allowEmptyShould(true)`~~ ——
      **不需要**:第 1 支最後是把整條規則移除,而非保留加 `allowEmptyShould`,
      所以沒有殘留可清。本支是重新加入這條規則,一開始就不需要該旗標(adapter 內已有方法)
- [x] 5.4 **反向驗證(逐條),四項全數確認變紅**:
      - `noTransactionalOnAdapterClasses` → `persistence/ProbeTxClass` 類別標 `@Transactional` → **✓**
      - `noTransactionalOnAdapterMethods` → `persistence/ProbeTxMethod` 方法標 `@Transactional` → **✓**
      - `jpaEntitiesDoNotLeakOutOfPersistence` → `application/service/ProbeEntityLeak` 持有 `EventJpaEntity` → **✓**
      - **以註解而非名稱判定** → `ProbeRecord`(標 `@Entity` 但不以 `JpaEntity` 結尾)外洩至 application → **✓**
      第四項是這條規則的關鍵驗收:若改以類別名稱結尾判定,它會漏掉。
      刪除樣本時連 `target/classes` 的 `.class` 一併刪除,還原後 37 測試全綠、`find` 無殘留
- [x] 5.5 更新 `HexagonalLayeringTest` 的類別 Javadoc:移除「@Transactional 守則不在此處」的說明,
      改為記錄「綠燈不是規則正確的證據,反向驗證才是」
- [x] 5.6 **驗證**:`./mvnw verify` 綠(37 個測試,7 條 ArchUnit 規則)

## 6. 收尾

- [x] 6.1 完整驗證鏈實際輸出:

      ```
      ########## ./mvnw compile ##########
      [INFO] BUILD SUCCESS

      ########## ./mvnw spotless:check ##########
      [INFO] Spotless.Java is keeping 47 files clean - 0 needs changes to be clean
      [INFO] BUILD SUCCESS

      ########## ./mvnw verify ##########
      [INFO] Tests run: 37, Failures: 0, Errors: 0, Skipped: 0
      [INFO] Building jar: target/ticket-rush-0.0.1-SNAPSHOT.jar
      [INFO] BUILD SUCCESS
      ```

      測試組成:ArchUnit 7、SchemaMigration 4、PersistenceAdapter 整合 5、JpaEntityMapper 5、
      Stock 領域 6、ValueObject 8、context 與 actuator 各 1。

- [x] 6.2 **本支引入的版本**:

      | 項目 | 版本 |
      | --- | --- |
      | spring-data-jpa | 4.1.1 |
      | Hibernate ORM | 7.4.5.Final |
      | Flyway | 12.4.0(`flyway-core` + `flyway-database-postgresql`) |
      | PostgreSQL driver | 42.7.13 |
      | Testcontainers | **2.0.5** —— 2.x,API 與 1.x 有差異,見 lessons |
- [x] 6.3 更新 `tasks/todo.md`:第 2 支打勾,「必須補上兩條延後規則」的註記已移除(規則已補上)
- [x] 6.4 `tasks/lessons.md`:寫入一則 —— Testcontainers 2.x 的非泛型 API,
      以及「`@Bean` 內 new 容器會因 context 快取分組而重複啟動」
- [x] 6.5 需要使用者手動執行的動作:`docker exec my-postgres createdb -U postgres ticket_rush_db`
      (**僅開發環境需要**;整合測試不碰它,Testcontainers 自行啟動容器)。
      另需在 `.env` 設定 `DEV_DB_PASSWORD`,格式見 `.env.example`
- [x] 6.6 archive 這支 change(`openspec archive add-domain-model -y`):
      `platform-persistence-model` 新建、`platform-hexagonal-layering` 更新,
      change 資料夾移至 `openspec/changes/archive/2026-09-06-add-domain-model/`
