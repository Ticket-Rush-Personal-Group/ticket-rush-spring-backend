package com.alantsai.ticketrush.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
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
 * <p>註解以字串形式指定而非 class 參考,讓守則得以先於相依存在 —— 這是「守則要先於被守護的
 * 程式碼」的實際做法。
 *
 * <p><b>@Transactional 的位置守則不在此處</b>:spring-tx 目前不在 classpath(相依只有
 * webmvc / validation / actuator),連違規樣本都造不出來,因此無法反向驗證。依 design 的 D2,
 * 該規則延後至第 2 支 {@code add-domain-model} —— 屆時 spring-data-jpa 會帶入 spring-tx。
 * 留下一條無法驗證的守則,比沒有守則更危險。
 */
@AnalyzeClasses(packages = "com.alantsai.ticketrush", importOptions = ImportOption.DoNotIncludeTests.class)
class HexagonalLayeringTest {

    private static final String SPRING_CROSS_ORIGIN = "org.springframework.web.bind.annotation.CrossOrigin";

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
}
