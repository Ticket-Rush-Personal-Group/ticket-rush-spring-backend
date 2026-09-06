package com.alantsai.ticketrush.infrastructure;

import com.alantsai.ticketrush.application.facade.StrategyRegistry;
import com.alantsai.ticketrush.application.metrics.RetryStatistics;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 應用關閉時輸出重試次數分佈。
 *
 * <p><b>這是 {@link RuntimeInfoLogger} 在關閉端的對應。</b> 啟動時報告執行環境,關閉時報告
 * 這一輪跑出來的重試行為 —— 同一個原則:**讓被測系統自己報告,而不是由人從設定檔抄。**
 * 第 4 支踩過的坑正是抄設定值:compose 的設定被靜默替換,抄到的是「應該是什麼」而不是「實際是什麼」。
 *
 * <p><b>為什麼在關閉時輸出而不是定期輸出:</b> 壓測期間的任何 I/O 都會影響被量測的數字。
 * 關閉時只印一行,對測量結果零影響。取得方式是壓測後 {@code docker compose down},
 * 再從 {@code docker compose logs app} 讀 —— 每組壓測本來就要重啟應用切換策略,不增加步驟。
 *
 * <p>分佈只印有計數的桶。上限 100 時全印會有 100 個 0,把真正的資訊淹掉。
 */
@Component
public class RetryStatisticsLogger {

    private static final Logger log = LoggerFactory.getLogger(RetryStatisticsLogger.class);
    private static final String OPTIMISTIC_STRATEGY = "optimistic";

    private final RetryStatistics statistics;
    private final StrategyRegistry strategyRegistry;

    public RetryStatisticsLogger(RetryStatistics statistics, StrategyRegistry strategyRegistry) {
        this.statistics = statistics;
        this.strategyRegistry = strategyRegistry;
    }

    @EventListener(ContextClosedEvent.class)
    public void logOnShutdown() {
        // 其他三層沒有重試，印出來只會是一片零，讓人以為量測失敗了。
        if (!OPTIMISTIC_STRATEGY.equals(strategyRegistry.current()) || statistics.totalRecorded() == 0) {
            return;
        }

        log.info(
                """

                ===== 重試次數分佈(樂觀鎖) =====
                重試上限     : {}
                已記錄請求   : {}
                最大嘗試次數 : {}
                平均嘗試次數 : {}
                重試耗盡     : {}
                分佈(次數:請求數):
                {}
                ==================================
                """,
                statistics.maxAttempts(),
                statistics.totalRecorded(),
                statistics.maxObservedAttempts(),
                "%.2f".formatted(statistics.averageAttempts()),
                statistics.exhaustedCount(),
                formatDistribution());
    }

    /**
     * 只列出有計數的桶。
     *
     * <p>平均值單獨看沒有意義,分佈才是本層的產出 —— 因此即使只有一行也要印分佈。
     */
    private String formatDistribution() {
        long[] distribution = statistics.distribution();
        return IntStream.rangeClosed(1, statistics.maxAttempts())
                .filter(attempts -> distribution[attempts] > 0)
                .mapToObj(attempts -> "  %d 次 : %d".formatted(attempts, distribution[attempts]))
                .collect(Collectors.joining("\n"));
    }
}
