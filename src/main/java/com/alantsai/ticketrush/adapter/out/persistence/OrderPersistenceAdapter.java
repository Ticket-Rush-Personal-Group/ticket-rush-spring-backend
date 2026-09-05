package com.alantsai.ticketrush.adapter.out.persistence;

import com.alantsai.ticketrush.adapter.out.persistence.entity.OrderJpaEntity;
import com.alantsai.ticketrush.adapter.out.persistence.mapper.JpaEntityMapper;
import com.alantsai.ticketrush.adapter.out.persistence.repository.OrderJpaRepository;
import com.alantsai.ticketrush.application.port.out.LoadUserPurchasedQuantityPort;
import com.alantsai.ticketrush.application.port.out.SaveOrderPort;
import com.alantsai.ticketrush.domain.model.Order;
import com.alantsai.ticketrush.domain.valueobject.EventId;
import com.alantsai.ticketrush.domain.valueobject.UserId;
import org.springframework.stereotype.Component;

/**
 * 訂單的持久化 adapter。
 *
 * <p>此處沒有 {@code @Transactional} —— 交易邊界屬於 application service,由 ArchUnit 強制。
 * 這不是疏漏:四層策略的差異有一半來自交易邊界的位置,把它固定在 adapter 會讓策略無法各自決定。
 */
@Component
public class OrderPersistenceAdapter implements SaveOrderPort, LoadUserPurchasedQuantityPort {

    private final OrderJpaRepository repository;

    public OrderPersistenceAdapter(OrderJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Order saveOrder(Order order) {
        OrderJpaEntity saved = repository.save(JpaEntityMapper.toEntity(order));
        return JpaEntityMapper.toDomain(saved);
    }

    @Override
    public int loadPurchasedQuantity(EventId eventId, UserId userId) {
        return repository.sumPurchasedQuantity(eventId.value(), userId.value());
    }
}
