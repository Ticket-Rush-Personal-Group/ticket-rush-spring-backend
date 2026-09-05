package com.alantsai.ticketrush.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * 六角分層的架構守則。
 *
 * <p>對應 spec:{@code platform-hexagonal-layering}。
 *
 * <p>註解以字串 FQN 指定而非 class 參考,讓守則得以先於相依存在 —— 這是「守則要先於被守護的
 * 程式碼」的實際做法,第 1 支即以此建立。
 *
 * <p>每一條規則都經過反向驗證:造出違規樣本、確認該條變紅、移除樣本。**綠燈不是規則正確的證據,
 * 反向驗證才是。**
 */
@AnalyzeClasses(packages = "com.alantsai.ticketrush", importOptions = ImportOption.DoNotIncludeTests.class)
class HexagonalLayeringTest {

    private static final String SPRING_TRANSACTIONAL = "org.springframework.transaction.annotation.Transactional";
    private static final String SPRING_CROSS_ORIGIN = "org.springframework.web.bind.annotation.CrossOrigin";
    private static final String JPA_ENTITY = "jakarta.persistence.Entity";
    private static final String PURCHASE_TICKET_USE_CASE =
            "com.alantsai.ticketrush.application.port.in.PurchaseTicketUseCase";

    private static final String PERSISTENCE_PACKAGE = "..adapter.out.persistence..";
    private static final String WEB_PACKAGE = "..adapter.in.web..";

    /**
     * R1:分層依賴方向只能由外向內。
     *
     * <p>adapter 不得被任何層存取;application 只能被 adapter 與 infrastructure 存取;
     * domain 可被其餘各層存取。infrastructure 不設限,因為 {@code @ConfigurationProperties}
     * 的設定類別本來就會被上層注入。
     */
    @ArchTest
    static final ArchRule layerDependencyDirection = layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer("Domain")
            .definedBy("..domain..")
            .layer("Application")
            .definedBy("..application..")
            .layer("Adapter")
            .definedBy("..adapter..")
            .layer("Infrastructure")
            .definedBy("..infrastructure..")
            .whereLayer("Adapter")
            .mayNotBeAccessedByAnyLayer()
            .whereLayer("Application")
            .mayOnlyBeAccessedByLayers("Adapter", "Infrastructure")
            .whereLayer("Domain")
            .mayOnlyBeAccessedByLayers("Application", "Adapter", "Infrastructure")
            .because("domain 依賴外層會使領域規則綁死在基礎設施上,四種併發策略就無法在不動 domain 的前提下抽換");

    /**
     * R2:domain 層不得依賴任何框架。
     *
     * <p>這是「四種策略切換、domain 零改動」這項主張唯一的技術基礎。一旦 domain 綁上 JPA,
     * 策略抽換就必然波及領域模型。
     */
    @ArchTest
    static final ArchRule domainIsFrameworkFree = noClasses()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("org.springframework..", "jakarta.persistence..", "com.fasterxml.jackson..")
            .because("domain 對使用何種鎖、何種持久化機制必須一無所知");

    /** R3:交易邊界只屬於 application 層,不得出現在 adapter(類別層級)。 */
    @ArchTest
    static final ArchRule noTransactionalOnAdapterClasses = noClasses()
            .that()
            .resideInAnyPackage(WEB_PACKAGE, PERSISTENCE_PACKAGE)
            .should()
            .beAnnotatedWith(SPRING_TRANSACTIONAL)
            .because("標在 controller 會把 HTTP 處理納入交易範圍,標在 repository 則無法跨多個 repository 保證原子性");

    /** R3:交易邊界只屬於 application 層,不得出現在 adapter(方法層級)。 */
    @ArchTest
    static final ArchRule noTransactionalOnAdapterMethods = noMethods()
            .that()
            .areDeclaredInClassesThat()
            .resideInAnyPackage(WEB_PACKAGE, PERSISTENCE_PACKAGE)
            .should()
            .beAnnotatedWith(SPRING_TRANSACTIONAL)
            .because("交易邊界是架構決策而非實作細節,只能由 application service 持有 ——" + "四層策略的差異有一半來自交易邊界的位置,固定在 adapter 會讓策略無法各自決定");

    /** R4:CORS 集中設定,不得使用 {@code @CrossOrigin}(類別層級)。 */
    @ArchTest
    static final ArchRule noCrossOriginOnClasses = noClasses()
            .should()
            .beAnnotatedWith(SPRING_CROSS_ORIGIN)
            .because("CORS 只在 infrastructure.config.WebConfig 設定一次;散落各處時,漏設的症狀是特定端點在瀏覽器失敗而在 curl 正常");

    /** R4:CORS 集中設定,不得使用 {@code @CrossOrigin}(方法層級)。 */
    @ArchTest
    static final ArchRule noCrossOriginOnMethods =
            noMethods().should().beAnnotatedWith(SPRING_CROSS_ORIGIN).because("方法層級的 @CrossOrigin 一樣會讓 CORS 設定分散");

    /**
     * R5:JPA entity 不得外洩持久化層。
     *
     * <p><b>以 {@code @Entity} 註解判定,不以類別名稱結尾判定。</b> 命名慣例可被繞過 ——
     * 把 entity 取名 {@code OrderRecord} 就避開了「以 JpaEntity 結尾」的檢查,而註解避不開。
     *
     * <p>entity 一旦外洩,持久化的細節就跟著擴散:延遲載入、關聯導覽、entity 生命週期,
     * 會滲進不該知道它們的層,屆時「domain 零改動」不再成立。
     */
    @ArchTest
    static final ArchRule jpaEntitiesDoNotLeakOutOfPersistence = noClasses()
            .that()
            .resideOutsideOfPackage(PERSISTENCE_PACKAGE)
            .should()
            .dependOnClassesThat()
            .areAnnotatedWith(JPA_ENTITY)
            .because("entity 是持久化的實作細節,外洩會讓 JPA 的行為擴散到 application 與 domain");

    /**
     * R6:{@code PurchaseTicketUseCase} 不得被單獨注入。
     *
     * <p>以「欄位型別」判定即可涵蓋建構子注入 —— 建構子參數最終都會賦值給欄位。
     * 這同時擋下 {@code @Qualifier} 繞過:即使指定了特定實作,欄位型別仍是該介面。
     *
     * <p><b>本規則真正要防的不是啟動失敗。</b> 單純的單一注入本來就會拋
     * {@code NoUniqueBeanDefinitionException},不需要守則。要防的是有人以 {@code @Qualifier}
     * 或 {@code @Primary} 讓它「能動」—— 那會讓某個類別直接綁定特定策略,
     * 策略不再可自由抽換,而且**不會有任何錯誤訊息**。
     *
     * <p>{@code PurchaseFacade} 注入的是 {@code Map<String, PurchaseTicketUseCase>},
     * 欄位型別為 Map,因此不受本規則影響 —— 集合注入是唯一允許的取用方式。
     */
    @ArchTest
    static final ArchRule purchaseUseCaseIsNeverInjectedDirectly = noFields()
            .should()
            .haveRawType(PURCHASE_TICKET_USE_CASE)
            .because("直接持有單一策略會讓該類別綁定特定實作,「同一個 API、四種實作」的前提就不成立了");
}
