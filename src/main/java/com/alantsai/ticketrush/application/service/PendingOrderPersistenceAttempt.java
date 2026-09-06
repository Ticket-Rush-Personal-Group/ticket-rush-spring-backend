package com.alantsai.ticketrush.application.service;

import com.alantsai.ticketrush.application.port.in.PurchaseTicketCommand;
import com.alantsai.ticketrush.application.port.out.OrderExistencePort;
import com.alantsai.ticketrush.application.port.out.SaveOrderPort;
import com.alantsai.ticketrush.domain.model.Order;
import java.time.Clock;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 落庫的**單次嘗試**,自成一個交易。
 *
 * <p><b>本類別與 {@link PersistPendingOrderService} 必須是兩個 bean</b> ——
 * 與樂觀鎖那層({@code OptimisticPurchaseAttempt} / {@code OptimisticPurchaseService})
 * 同一個模式、同一個理由:
 *
 * <ul>
 *   <li><b>交易邊界必須止於資料庫寫入。</b> 補償是 Redis 操作,它若被納入資料庫交易,
 *       會產生「交易回滾了但 Redis 已回補」的組合 —— 庫存被還了兩次。
 *   <li><b>同類別的自我呼叫會繞過 AOP proxy</b>,交易完全不生效,而且沒有任何錯誤訊息。
 *       由 ArchUnit 守則強制。
 * </ul>
 *
 * <p><b>重複落庫的兩道檢查:</b>
 *
 * <ol>
 *   <li>先查冪等鍵是否存在 —— 訊息重送是預期中的常見路徑,不該靠例外做流程控制
 *   <li>唯一約束 —— 查詢與寫入之間仍有競態(兩個消費者同時領到同一則訊息)。
 *       違反時由持久化 adapter 翻譯為 {@code DuplicateOrderException} 並往外拋
 * </ol>
 *
 * <p><b>約束違反刻意不在本方法內捕捉。</b> 交易在例外發生時已被標記為 rollback-only;
 * 若在此攔下並正常回傳,提交階段會拋 {@code UnexpectedRollbackException} ——
 * 一個看起來與原因完全無關的錯誤。
 */
@Component
public class PendingOrderPersistenceAttempt {

    private final SaveOrderPort saveOrderPort;
    private final OrderExistencePort orderExistencePort;
    private final Clock clock;

    public PendingOrderPersistenceAttempt(
            SaveOrderPort saveOrderPort, OrderExistencePort orderExistencePort, Clock clock) {
        this.saveOrderPort = saveOrderPort;
        this.orderExistencePort = orderExistencePort;
        this.clock = clock;
    }

    /**
     * 嘗試落庫一次。
     *
     * @return {@code true} 已寫入;{@code false} 冪等鍵已存在,先前已成功落庫過
     * @throws com.alantsai.ticketrush.application.exception.DuplicateOrderException
     *     查詢與寫入之間的競態導致約束違反 —— 語意同 {@code false},但由例外路徑抵達
     */
    @Transactional
    public boolean tryPersist(PurchaseTicketCommand command) {
        if (orderExistencePort.exists(command.idempotencyKey())) {
            return false;
        }

        saveOrderPort.saveOrder(Order.newOrder(
                command.eventId(), command.userId(), command.quantity(), command.idempotencyKey(), clock.instant()));

        return true;
    }
}
