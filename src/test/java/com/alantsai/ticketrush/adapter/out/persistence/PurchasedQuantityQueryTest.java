package com.alantsai.ticketrush.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.alantsai.ticketrush.application.port.out.LoadUserPurchasedQuantityPort;
import com.alantsai.ticketrush.domain.valueobject.EventId;
import com.alantsai.ticketrush.domain.valueobject.UserId;
import com.alantsai.ticketrush.testsupport.TestcontainersConfiguration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 累計購買張數查詢的整合測試。
 *
 * <p>限購的正確性完全建立在這個查詢上。**它最容易錯的地方是用訂單筆數取代張數總和** ——
 * 那個錯誤在「每筆都買 1 張」的測試資料下完全看不出來,因此測試資料刻意使用不同的張數。
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PurchasedQuantityQueryTest {

    @Autowired
    private LoadUserPurchasedQuantityPort loadUserPurchasedQuantityPort;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void cleanup() {
        jdbc.execute("TRUNCATE purchase_order, stock, event RESTART IDENTITY CASCADE");
    }

    private long givenEvent(String name) {
        return jdbc.queryForObject(
                """
                INSERT INTO event (name, sales_start_at, total_quantity)
                VALUES (?, ?, ?) RETURNING id
                """, Long.class, name, java.sql.Timestamp.from(Instant.parse("2026-12-01T12:00:00Z")), 500);
    }

    private void givenOrder(long eventId, long userId, int quantity, String key) {
        jdbc.update("""
                INSERT INTO purchase_order (event_id, user_id, quantity, status, idempotency_key)
                VALUES (?, ?, ?, 'PENDING', ?)
                """, eventId, userId, quantity, key);
    }

    @Test
    @DisplayName("無訂單時回 0,而非 null")
    void returnsZeroWhenNoOrders() {
        long eventId = givenEvent("測試場次");

        int quantity = loadUserPurchasedQuantityPort.loadPurchasedQuantity(new EventId(eventId), new UserId(1L));

        assertThat(quantity).isZero();
    }

    @Test
    @DisplayName("多筆訂單回張數總和,不是訂單筆數")
    void sumsQuantitiesNotOrderCount() {
        long eventId = givenEvent("測試場次");
        // 三筆訂單、合計 6 張。若實作誤用 count(*) 會得到 3。
        givenOrder(eventId, 1L, 1, "k1");
        givenOrder(eventId, 1L, 2, "k2");
        givenOrder(eventId, 1L, 3, "k3");

        int quantity = loadUserPurchasedQuantityPort.loadPurchasedQuantity(new EventId(eventId), new UserId(1L));

        assertThat(quantity).isEqualTo(6);
    }

    @Test
    @DisplayName("不同場次互不影響 —— 限購是每人每場次,不是每人全站")
    void isolatedPerEvent() {
        long eventA = givenEvent("場次 A");
        long eventB = givenEvent("場次 B");
        givenOrder(eventA, 1L, 4, "k-a");
        givenOrder(eventB, 1L, 2, "k-b");

        assertThat(loadUserPurchasedQuantityPort.loadPurchasedQuantity(new EventId(eventA), new UserId(1L)))
                .isEqualTo(4);
        assertThat(loadUserPurchasedQuantityPort.loadPurchasedQuantity(new EventId(eventB), new UserId(1L)))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("不同使用者互不影響")
    void isolatedPerUser() {
        long eventId = givenEvent("測試場次");
        givenOrder(eventId, 1L, 3, "k-u1");
        givenOrder(eventId, 2L, 5, "k-u2");

        assertThat(loadUserPurchasedQuantityPort.loadPurchasedQuantity(new EventId(eventId), new UserId(1L)))
                .isEqualTo(3);
        assertThat(loadUserPurchasedQuantityPort.loadPurchasedQuantity(new EventId(eventId), new UserId(2L)))
                .isEqualTo(5);
    }
}
