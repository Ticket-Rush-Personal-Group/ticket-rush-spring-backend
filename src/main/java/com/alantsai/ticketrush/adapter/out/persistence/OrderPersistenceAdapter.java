package com.alantsai.ticketrush.adapter.out.persistence;

import com.alantsai.ticketrush.adapter.out.persistence.entity.OrderJpaEntity;
import com.alantsai.ticketrush.adapter.out.persistence.mapper.JpaEntityMapper;
import com.alantsai.ticketrush.adapter.out.persistence.repository.OrderJpaRepository;
import com.alantsai.ticketrush.application.exception.DuplicateOrderException;
import com.alantsai.ticketrush.application.port.out.LoadEventSoldQuantityPort;
import com.alantsai.ticketrush.application.port.out.LoadUserPurchasedQuantityPort;
import com.alantsai.ticketrush.application.port.out.OrderExistencePort;
import com.alantsai.ticketrush.application.port.out.SaveOrderPort;
import com.alantsai.ticketrush.domain.model.Order;
import com.alantsai.ticketrush.domain.valueobject.EventId;
import com.alantsai.ticketrush.domain.valueobject.IdempotencyKey;
import com.alantsai.ticketrush.domain.valueobject.UserId;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * 訂單的持久化 adapter。
 *
 * <p>此處沒有 {@code @Transactional} —— 交易邊界屬於 application service,由 ArchUnit 強制。
 * 這不是疏漏:四層策略的差異有一半來自交易邊界的位置,把它固定在 adapter 會讓策略無法各自決定。
 */
@Component
public class OrderPersistenceAdapter
        implements SaveOrderPort, LoadUserPurchasedQuantityPort, OrderExistencePort, LoadEventSoldQuantityPort {

    private final OrderJpaRepository repository;

    public OrderPersistenceAdapter(OrderJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Order saveOrder(Order order) {
        try {
            OrderJpaEntity saved = repository.save(JpaEntityMapper.toEntity(order));
            return JpaEntityMapper.toDomain(saved);
        } catch (DataIntegrityViolationException e) {
            // adapter 的職責之一：把基礎設施的例外翻譯成應用層看得懂的語彙。
            // application 不得自己去辨識約束名稱——那是持久化細節，而且會讓 application 依賴 adapter。
            if (OrderConstraints.isIdempotencyKeyViolation(
                    e.getMostSpecificCause().getMessage())) {
                throw new DuplicateOrderException(order.idempotencyKey(), e);
            }
            throw e;
        }
    }

    @Override
    public int loadPurchasedQuantity(EventId eventId, UserId userId) {
        return repository.sumPurchasedQuantity(eventId.value(), userId.value());
    }

    /**
     * 冪等鍵是否已存在。
     *
     * <p>供第 3 層的落庫消費者判斷訊息是否重送。唯一約束仍是最終保證 ——
     * 本查詢只是讓常見情況不必依賴例外流程。
     */
    @Override
    public boolean exists(IdempotencyKey idempotencyKey) {
        return repository.existsByIdempotencyKey(idempotencyKey.value());
    }

    /**
     * 某場次已售出的張數總和。**對帳用。**
     *
     * <p>以張數而非訂單筆數計算 —— 對帳比對的是「Redis 扣了幾張」與「資料庫記了幾張」。
     */
    @Override
    public int loadSoldQuantity(EventId eventId) {
        return repository.sumSoldQuantity(eventId.value());
    }
}
