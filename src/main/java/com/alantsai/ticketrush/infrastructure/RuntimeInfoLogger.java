package com.alantsai.ticketrush.infrastructure;

import com.alantsai.ticketrush.application.facade.StrategyRegistry;
import com.alantsai.ticketrush.application.metrics.RetryStatistics;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.apache.coyote.AbstractProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 啟動時記錄 JVM 實際觀察到的執行環境。
 *
 * <p><b>這不是除錯用的輸出,而是壓測測量條件的來源。</b> 測量條件若由人抄寫 compose 設定,
 * 抄的是「設定值」而非「JVM 實際看到的值」—— 而兩者不一致正是最需要被發現的問題:
 * cgroup 限制在不同的容器執行環境(OrbStack / Docker Desktop / Colima)未必以相同方式生效,
 * 失效時的症狀只是「數字看起來怪怪的」,不會產生任何錯誤。
 *
 * <p>{@code availableProcessors} 尤其關鍵:虛擬執行緒的 carrier thread 數量預設等於它,
 * 若它不等於 compose 設定的 {@code cpus},該次壓測的並行度基準就與其他組別不同,數據不可比較。
 */
@Component
public class RuntimeInfoLogger implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RuntimeInfoLogger.class);
    private static final long BYTES_PER_MB = 1024L * 1024L;

    private final Environment environment;
    private final StrategyRegistry strategyRegistry;
    private final DataSource dataSource;
    private final RetryStatistics retryStatistics;
    private final long reconciliationIntervalMs;
    private final ApplicationContext applicationContext;

    public RuntimeInfoLogger(
            Environment environment,
            StrategyRegistry strategyRegistry,
            DataSource dataSource,
            RetryStatistics retryStatistics,
            @Value("${ticket-rush.redis.reconciliation-interval-ms}") long reconciliationIntervalMs,
            ApplicationContext applicationContext) {
        this.environment = environment;
        this.strategyRegistry = strategyRegistry;
        this.dataSource = dataSource;
        this.retryStatistics = retryStatistics;
        this.reconciliationIntervalMs = reconciliationIntervalMs;
        this.applicationContext = applicationContext;
    }

    @Override
    public void run(ApplicationArguments args) {
        Runtime runtime = Runtime.getRuntime();
        boolean virtualThreads =
                Boolean.TRUE.equals(environment.getProperty("spring.threads.virtual.enabled", Boolean.class, false));

        log.info(
                """

                ===== 執行環境(壓測測量條件的來源) =====
                availableProcessors : {}
                maxMemory (heap)    : {} MB
                連線池上限          : {}
                准入併發度上限      : {}
                重試上限(樂觀鎖)    : {}
                對帳間隔(Redis 預扣): {} ms
                Redis               : {}
                虛擬執行緒          : {}
                當前策略            : {}
                ==========================================
                """,
                runtime.availableProcessors(),
                runtime.maxMemory() / BYTES_PER_MB,
                maxPoolSize(),
                // 執行緒模型只說明「用什麼執行緒」,不說明「同時放進來幾個」——
                // 而後者直接決定有多少請求同時競爭連線池與資料庫的列鎖。
                admissionLimit(),
                // 從持有它的 bean 讀取，不讀設定值——與 CPU / heap / 連線池同樣的理由：
                // 要報告的是實際生效的值。它只對樂觀鎖有意義，但仍一律輸出，
                // 因為測量條件的表格不該有「這一組沒有這個欄位」的空洞。
                retryStatistics.maxAttempts(),
                reconciliationIntervalMs,
                // Redis 也是測量條件的一部分：**第 0/1/2 層完全不碰它**，
                // 四層並列時必須看得出哪一層多用了一個元件。
                environment.getProperty("spring.data.redis.host", "未知") + ":"
                        + environment.getProperty("spring.data.redis.port", "未知"),
                virtualThreads ? "啟用" : "停用(平台執行緒)",
                strategyRegistry.current());
    }

    /**
     * 連線池上限。
     *
     * <p>從 DataSource 實例讀取而非讀設定值 —— 與 CPU / heap 同樣的理由:
     * 要報告的是**實際生效的值**,設定被覆蓋或未生效時才看得出來。
     */
    private String maxPoolSize() {
        if (dataSource instanceof HikariDataSource hikari) {
            return String.valueOf(hikari.getMaximumPoolSize());
        }
        return "未知(非 HikariCP:" + dataSource.getClass().getSimpleName() + ")";
    }

    /**
     * 同時可進入應用處理的請求數上限。
     *
     * <p><b>取自 Tomcat 實際的 protocol handler,不取自 {@code server.tomcat.threads.max} 設定值。</b>
     * 理由與連線池、CPU、heap 相同,但這裡的後果更嚴重:**啟用虛擬執行緒之後,
     * 該設定仍可被賦值卻不再約束請求處理** —— 讀設定值會在條件表上寫下一個不是事實的數字,
     * 而**條件表上錯誤的數字比缺漏的數字更危險:缺漏看得出來,錯誤看不出來。**
     *
     * <p>虛擬執行緒下 endpoint 的 executor 不是有上限的執行緒池,Tomcat 因此回傳 -1 ——
     * 那正是「不受此上限約束」的實際訊號,據實呈現而不換算成任何數字。
     */
    private String admissionLimit() {
        if (!(applicationContext instanceof ServletWebServerApplicationContext servletContext)) {
            return "未知(非 Servlet 容器)";
        }
        WebServer webServer = servletContext.getWebServer();
        if (!(webServer instanceof TomcatWebServer tomcat)) {
            return "未知(非 Tomcat:" + webServer.getClass().getSimpleName() + ")";
        }
        if (!(tomcat.getTomcat().getConnector().getProtocolHandler() instanceof AbstractProtocol<?> protocol)) {
            return "未知(非 AbstractProtocol)";
        }
        int maxThreads = protocol.getMaxThreads();
        return maxThreads < 0 ? "不適用(虛擬執行緒,請求處理不受執行緒池上限約束)" : String.valueOf(maxThreads);
    }
}
