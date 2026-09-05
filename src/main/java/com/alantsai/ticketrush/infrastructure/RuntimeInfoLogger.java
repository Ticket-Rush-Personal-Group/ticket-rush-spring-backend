package com.alantsai.ticketrush.infrastructure;

import com.alantsai.ticketrush.application.facade.StrategyRegistry;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
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

    public RuntimeInfoLogger(Environment environment, StrategyRegistry strategyRegistry, DataSource dataSource) {
        this.environment = environment;
        this.strategyRegistry = strategyRegistry;
        this.dataSource = dataSource;
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
                虛擬執行緒          : {}
                當前策略            : {}
                ==========================================
                """,
                runtime.availableProcessors(),
                runtime.maxMemory() / BYTES_PER_MB,
                maxPoolSize(),
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
}
