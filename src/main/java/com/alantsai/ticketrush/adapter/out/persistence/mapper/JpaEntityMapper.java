package com.alantsai.ticketrush.adapter.out.persistence.mapper;

import com.alantsai.ticketrush.adapter.out.persistence.entity.EventJpaEntity;
import com.alantsai.ticketrush.adapter.out.persistence.entity.OrderJpaEntity;
import com.alantsai.ticketrush.adapter.out.persistence.entity.StockJpaEntity;
import com.alantsai.ticketrush.domain.model.Event;
import com.alantsai.ticketrush.domain.model.Order;
import com.alantsai.ticketrush.domain.model.OrderStatus;
import com.alantsai.ticketrush.domain.model.Stock;
import com.alantsai.ticketrush.domain.valueobject.EventId;
import com.alantsai.ticketrush.domain.valueobject.IdempotencyKey;
import com.alantsai.ticketrush.domain.valueobject.OrderId;
import com.alantsai.ticketrush.domain.valueobject.Quantity;
import com.alantsai.ticketrush.domain.valueobject.UserId;

/**
 * domain model 與 JPA entity 的雙向轉換。
 *
 * <p>手寫而非使用 MapStruct:三個型別的轉換不到百行,而 MapStruct 需要 annotation processor 設定,
 * 且產生的程式碼位於 {@code target/} —— 排查轉換錯誤時要去看產生物。entity 數量成長到十個以上再重新評估。
 *
 * <p>轉換是分離型別的代價,也是「四種策略切換、domain 零改動」的實際支撐。
 */
public final class JpaEntityMapper {

    private JpaEntityMapper() {}

    public static Event toDomain(EventJpaEntity entity) {
        return new Event(
                new EventId(entity.getId()), entity.getName(), entity.getSalesStartAt(), entity.getTotalQuantity());
    }

    public static Stock toDomain(StockJpaEntity entity) {
        return new Stock(new EventId(entity.getEventId()), entity.getAvailable(), entity.getVersion());
    }

    public static Order toDomain(OrderJpaEntity entity) {
        return new Order(
                entity.getId() == null ? null : new OrderId(entity.getId()),
                new EventId(entity.getEventId()),
                new UserId(entity.getUserId()),
                new Quantity(entity.getQuantity()),
                OrderStatus.valueOf(entity.getStatus()),
                new IdempotencyKey(entity.getIdempotencyKey()),
                entity.getCreatedAt());
    }

    public static EventJpaEntity toEntity(Event event) {
        return new EventJpaEntity(
                event.id() == null ? null : event.id().value(),
                event.name(),
                event.salesStartAt(),
                event.totalQuantity());
    }

    public static StockJpaEntity toEntity(Stock stock) {
        return new StockJpaEntity(stock.eventId().value(), stock.available(), stock.version());
    }

    public static OrderJpaEntity toEntity(Order order) {
        return new OrderJpaEntity(
                order.id() == null ? null : order.id().value(),
                order.eventId().value(),
                order.userId().value(),
                order.quantity().value(),
                order.status().name(),
                order.idempotencyKey().value(),
                order.createdAt(),
                null);
    }
}
