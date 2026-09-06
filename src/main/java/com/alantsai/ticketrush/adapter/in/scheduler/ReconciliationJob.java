package com.alantsai.ticketrush.adapter.in.scheduler;

import com.alantsai.ticketrush.application.facade.StrategyRegistry;
import com.alantsai.ticketrush.application.port.in.ReconcileStockUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 週期對帳。入站 adapter,只呼叫 in port。
 *
 * <p><b>只在第 3 層生效。</b> 其他三層不使用快取,對一個沒人在扣的快取做對帳沒有意義,
 * 而且會在測試與壓測中製造出無關的寫入 —— 那會汙染另外三層的數據。
 *
 * <p>間隔可設定。**它是測量條件的一部分**:間隔太長,收斂在壓測結束前看不到;
 * 太短則對帳本身成為負載。兩者都會改變本層的數字。
 */
@Component
public class ReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationJob.class);
    private static final String REDIS_PRE_DEDUCT = "redisPreDeduct";

    private final ReconcileStockUseCase reconcileStockUseCase;
    private final StrategyRegistry strategyRegistry;

    public ReconciliationJob(ReconcileStockUseCase reconcileStockUseCase, StrategyRegistry strategyRegistry) {
        this.reconcileStockUseCase = reconcileStockUseCase;
        this.strategyRegistry = strategyRegistry;
    }

    @Scheduled(fixedDelayString = "${ticket-rush.redis.reconciliation-interval-ms}")
    public void reconcile() {
        if (!REDIS_PRE_DEDUCT.equals(strategyRegistry.current())) {
            return;
        }

        try {
            reconcileStockUseCase.reconcileAll();
        } catch (RuntimeException e) {
            // 對帳失敗不得讓排程停止。Spring 的 @Scheduled 在方法拋出例外時
            // **不會**中止後續執行，但仍會把整個堆疊印成 ERROR——
            // 壓測期間那會把日誌淹掉，真正的錯誤反而看不見。
            log.error("對帳失敗,將於下個週期重試", e);
        }
    }
}
