package com.alantsai.ticketrush.adapter.in.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import com.alantsai.ticketrush.adapter.out.redis.RedisKeys;
import com.alantsai.ticketrush.application.port.in.PurchaseTicketCommand;
import com.alantsai.ticketrush.application.port.in.ReconcileStockUseCase;
import com.alantsai.ticketrush.application.port.in.ReconciliationResult;
import com.alantsai.ticketrush.application.port.out.OrderStreamPort;
import com.alantsai.ticketrush.application.port.out.PreDeductResult;
import com.alantsai.ticketrush.application.port.out.StockCachePort;
import com.alantsai.ticketrush.domain.valueobject.EventId;
import com.alantsai.ticketrush.domain.valueobject.IdempotencyKey;
import com.alantsai.ticketrush.domain.valueobject.Quantity;
import com.alantsai.ticketrush.domain.valueobject.UserId;
import com.alantsai.ticketrush.testsupport.TestcontainersConfiguration;
import java.time.Duration;
import java.time.Instant;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * 即時補償與週期對帳的整合驗證(對真實 Redis 與 PostgreSQL)。
 *
 * <p><b>落庫失敗以真實的外鍵違反注入,不用 mock,也不在 production 留故障開關。</b>
 * 對一個不存在於資料庫的場次投遞訊息,落庫時就會撞上 {@code purchase_order.event_id}
 * 的外鍵約束 —— 那是一個貨真價實的失敗,而且完全不需要為了測試改動任何正式程式碼。
 * 一個「能讓落庫失敗」的設定開關本身就是風險。
 *
 * <p><b>排程對帳在本測試中關閉(間隔設得極長),改為直接呼叫 use case。</b>
 * 要驗的是對帳的結果,而不是「排程有沒有準時觸發」—— 讓背景執行緒在斷言中途插手,
 * 只會把一個確定性的測試變成偶發失敗。
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(
        properties = {
            "ticket-rush.redis.stream=orders-compensation",
            "ticket-rush.redis.reconciliation-interval-ms=3600000"
        })
class CompensationAndReconciliationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final int ALLOCATED = 500;
    private static final int LIMIT = 4;

    @Autowired
    private OrderStreamPort orderStreamPort;

    @Autowired
    private StockCachePort stockCachePort;

    @Autowired
    private ReconcileStockUseCase reconcileStockUseCase;

    @Autowired
    private OrderPersistenceConsumer consumer;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StringRedisTemplate redis;

    @Test
    @DisplayName("落庫失敗 → 庫存與已購數皆被回補,且訊息被 ack")
    void compensatesWhenPersistenceFails() {
        // 不存在於資料庫的場次：落庫時必定撞上外鍵約束。
        EventId ghost = new EventId(999_000L + System.nanoTime() % 1000);
        UserId user = new UserId(1L);
        redis.opsForValue().set(RedisKeys.stock(ghost), String.valueOf(ALLOCATED));

        assertThat(stockCachePort.preDeduct(ghost, user, new Quantity(2), LIMIT))
                .isEqualTo(PreDeductResult.SUCCESS);
        assertThat(stockCachePort.available(ghost)).hasValue(ALLOCATED - 2);

        long before = consumer.compensations();
        orderStreamPort.publish(new PurchaseTicketCommand(
                ghost, user, new Quantity(2), new IdempotencyKey("compensate-" + ghost.value())));

        await(() -> consumer.compensations() > before);

        assertThat(stockCachePort.available(ghost)).as("庫存必須被回補").hasValue(ALLOCATED);
        assertThat(stockCachePort.purchasedBy(ghost, user)).as("已購數必須一併回補").isZero();
        // 已補償的訊息必須 ack。留在 pending 的話，對帳會因保守而停止回補，差額就卡住不動。
        await(this::backlogEmpty);

        redis.delete(RedisKeys.stock(ghost));
    }

    @Test
    @DisplayName("預扣後訊息遺失 → 對帳在 pending 為空時把庫存補回,差額收斂為 0")
    void reconciliationConverges() {
        long eventId = givenEventWithAllocation();
        EventId event = new EventId(eventId);
        UserId user = new UserId(1L);

        // 預扣但**不投遞** —— 模擬「訊息在投遞前就遺失」，那是即時補償抓不到的情況。
        assertThat(stockCachePort.preDeduct(event, user, new Quantity(3), LIMIT))
                .isEqualTo(PreDeductResult.SUCCESS);
        assertThat(stockCachePort.available(event)).hasValue(ALLOCATED - 3);

        await(this::backlogEmpty);
        ReconciliationResult result = reconcileStockUseCase.reconcile(event);

        assertThat(result.preDeducted()).isEqualTo(3);
        assertThat(result.sold()).isZero();
        assertThat(result.discrepancy()).isEqualTo(3);
        assertThat(result.restored()).isTrue();
        assertThat(stockCachePort.available(event)).as("庫存已補回").hasValue(ALLOCATED);

        // **刻意驗證這個不對稱**：已購數沒有被回補。
        // 對帳算出的是聚合差額，它不知道那些扣減屬於誰——因此只能還庫存，還不了限購額度。
        // 方向是安全的：那位使用者少買到票（保守），而不是多買到票（超賣）。
        assertThat(stockCachePort.purchasedBy(event, user))
                .as("對帳不回補已購數 —— 寧可漏補,不可誤補")
                .isEqualTo(3);

        // 再對一次帳：已經收斂，不該再補。
        ReconciliationResult second = reconcileStockUseCase.reconcile(event);
        assertThat(second.discrepancy()).isZero();
        assertThat(second.converged()).isTrue();
        assertThat(stockCachePort.available(event)).hasValue(ALLOCATED);
    }

    /** 完全沒有訊息還在飛 —— 已投遞未 ack 的與尚未投遞的都算。 */
    private boolean backlogEmpty() {
        return orderStreamPort.backlog().isEmpty();
    }

    private void await(BooleanSupplier condition) {
        Instant deadline = Instant.now().plus(TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待被中斷", e);
            }
        }
        throw new AssertionError("等待逾時(%s):條件未成立".formatted(TIMEOUT));
    }

    /** 場次與其配額。第 3 層不扣減資料庫的 {@code stock.available},它保留為初始配額。 */
    private long givenEventWithAllocation() {
        Long eventId = jdbc.queryForObject(
                """
                INSERT INTO event (name, sales_start_at, total_quantity)
                VALUES (?, ?, ?) RETURNING id
                """, Long.class, "對帳測試場次", java.sql.Timestamp.from(Instant.parse("2026-12-01T12:00:00Z")), ALLOCATED);
        jdbc.update("INSERT INTO stock (event_id, available) VALUES (?, ?)", eventId, ALLOCATED);
        redis.opsForValue().set(RedisKeys.stock(new EventId(eventId)), String.valueOf(ALLOCATED));
        return eventId;
    }
}
