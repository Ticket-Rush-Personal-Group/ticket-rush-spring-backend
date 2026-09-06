package com.alantsai.ticketrush.application.service;

import com.alantsai.ticketrush.application.exception.DuplicateOrderException;
import com.alantsai.ticketrush.application.port.in.PersistOutcome;
import com.alantsai.ticketrush.application.port.in.PersistPendingOrderUseCase;
import com.alantsai.ticketrush.application.port.in.PurchaseTicketCommand;
import com.alantsai.ticketrush.application.port.out.StockCachePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 落庫的協調者:嘗試落庫,失敗則**回補快取的預扣**。
 *
 * <p><b>本類別刻意不標 {@code @Transactional}。</b> 補償是 Redis 操作 ——
 * 它若落在資料庫交易之內,會產生「交易回滾了但 Redis 已回補」的組合,庫存被還了兩次。
 * 交易只包住資料庫寫入,由 {@link PendingOrderPersistenceAttempt} 持有。
 *
 * <p><b>三種結果,而且三種都要 ack:</b>
 *
 * <table border="1">
 *   <caption>處置</caption>
 *   <tr><th>結果</th><th>意義</th><th>為什麼不回補</th></tr>
 *   <tr><td>{@code PERSISTED}</td><td>已寫入</td><td>票確實賣出去了</td></tr>
 *   <tr><td>{@code DUPLICATE}</td><td>先前已寫入</td>
 *       <td><b>票已經賣出去了,回補它就是超賣</b></td></tr>
 *   <tr><td>{@code COMPENSATED}</td><td>寫入失敗,已回補</td><td>——(這一種才回補)</td></tr>
 * </table>
 *
 * <p><b>回補本身失敗時不吞掉例外。</b> 那時訊息不該被 ack —— 讓它留在 pending,
 * 由重新領取或對帳處理。吞掉的話,那筆庫存會永遠消失,而且沒有任何痕跡。
 */
@Service
public class PersistPendingOrderService implements PersistPendingOrderUseCase {

    private static final Logger log = LoggerFactory.getLogger(PersistPendingOrderService.class);

    private final PendingOrderPersistenceAttempt attempt;
    private final StockCachePort stockCachePort;

    public PersistPendingOrderService(PendingOrderPersistenceAttempt attempt, StockCachePort stockCachePort) {
        this.attempt = attempt;
        this.stockCachePort = stockCachePort;
    }

    @Override
    public PersistOutcome persist(PurchaseTicketCommand command) {
        try {
            return attempt.tryPersist(command) ? PersistOutcome.PERSISTED : PersistOutcome.DUPLICATE;
        } catch (DuplicateOrderException e) {
            // 查詢與寫入之間的競態：兩個消費者同時領到同一則訊息。
            // **把它當成落庫失敗會回補一筆已經賣出去的庫存——那就是超賣。**
            return PersistOutcome.DUPLICATE;
        } catch (RuntimeException e) {
            log.warn(
                    "落庫失敗,回補快取預扣:場次 {}、冪等鍵 {}",
                    command.eventId().value(),
                    command.idempotencyKey().value(),
                    e);
            // 回補失敗時本方法會往外拋，消費者因此不會 ack——
            // 訊息留在 pending，由重新領取或對帳收拾。吞掉的話那筆庫存會無聲消失。
            stockCachePort.restore(command.eventId(), command.userId(), command.quantity());
            return PersistOutcome.COMPENSATED;
        }
    }
}
