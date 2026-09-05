package com.alantsai.ticketrush.adapter.out.persistence.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.alantsai.ticketrush.domain.model.Event;
import com.alantsai.ticketrush.domain.model.Order;
import com.alantsai.ticketrush.domain.model.OrderStatus;
import com.alantsai.ticketrush.domain.model.Stock;
import com.alantsai.ticketrush.domain.valueobject.EventId;
import com.alantsai.ticketrush.domain.valueobject.IdempotencyKey;
import com.alantsai.ticketrush.domain.valueobject.OrderId;
import com.alantsai.ticketrush.domain.valueobject.Quantity;
import com.alantsai.ticketrush.domain.valueobject.UserId;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * domain 與 JPA entity 的雙向轉換測試。純單元測試,不需要資料庫。
 *
 * <p>轉換是型別分離的代價。若這裡出錯,症狀會是「資料存進去了但讀出來少一個欄位」——
 * 整合測試若只斷言部分欄位就會漏掉,因此這裡逐欄位比對。
 */
class JpaEntityMapperTest {

    private static final Instant SALES_START = Instant.parse("2026-12-01T12:00:00Z");
    private static final Instant CREATED_AT = Instant.parse("2026-09-06T00:00:00Z");

    @Test
    @DisplayName("場次雙向轉換後所有欄位相等")
    void eventRoundTripPreservesAllFields() {
        Event original = new Event(new EventId(7L), "測試場次", SALES_START, 500);

        Event result = JpaEntityMapper.toDomain(JpaEntityMapper.toEntity(original));

        assertThat(result).isEqualTo(original);
    }

    @Test
    @DisplayName("庫存雙向轉換後所有欄位相等,含 version")
    void stockRoundTripPreservesAllFields() {
        Stock original = new Stock(new EventId(7L), 480, 3L);

        Stock result = JpaEntityMapper.toDomain(JpaEntityMapper.toEntity(original));

        assertThat(result).isEqualTo(original);
    }

    @Test
    @DisplayName("已持久化的訂單雙向轉換後所有欄位相等")
    void persistedOrderRoundTripPreservesAllFields() {
        Order original = new Order(
                new OrderId(42L),
                new EventId(7L),
                new UserId(3L),
                new Quantity(2),
                OrderStatus.PENDING,
                new IdempotencyKey("key-001"),
                CREATED_AT);

        Order result = JpaEntityMapper.toDomain(JpaEntityMapper.toEntity(original));

        assertThat(result).isEqualTo(original);
    }

    @Test
    @DisplayName("未持久化的訂單轉換後 id 仍為 null,不會被填成 0")
    void newOrderRoundTripKeepsNullId() {
        Order original = Order.newOrder(
                new EventId(7L), new UserId(3L), new Quantity(1), new IdempotencyKey("key-002"), CREATED_AT);

        Order result = JpaEntityMapper.toDomain(JpaEntityMapper.toEntity(original));

        assertThat(result.id()).isNull();
        assertThat(result.isPersisted()).isFalse();
        assertThat(result).isEqualTo(original);
    }

    @Test
    @DisplayName("訂單狀態以字串往返,列舉值不失真")
    void orderStatusSurvivesStringRoundTrip() {
        for (OrderStatus status : OrderStatus.values()) {
            Order original = new Order(
                    new OrderId(1L),
                    new EventId(7L),
                    new UserId(3L),
                    new Quantity(1),
                    status,
                    new IdempotencyKey("key-" + status),
                    CREATED_AT);

            Order result = JpaEntityMapper.toDomain(JpaEntityMapper.toEntity(original));

            assertThat(result.status()).isEqualTo(status);
        }
    }
}
