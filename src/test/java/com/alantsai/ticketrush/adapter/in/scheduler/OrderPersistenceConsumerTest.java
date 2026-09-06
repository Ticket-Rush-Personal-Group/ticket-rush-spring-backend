package com.alantsai.ticketrush.adapter.in.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import com.alantsai.ticketrush.application.port.in.PurchaseTicketCommand;
import com.alantsai.ticketrush.application.port.out.OrderStreamPort;
import com.alantsai.ticketrush.domain.valueobject.EventId;
import com.alantsai.ticketrush.domain.valueobject.IdempotencyKey;
import com.alantsai.ticketrush.domain.valueobject.Quantity;
import com.alantsai.ticketrush.domain.valueobject.UserId;
import com.alantsai.ticketrush.testsupport.TestcontainersConfiguration;
import java.time.Duration;
import java.time.Instant;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * Stream 消費者的落庫行為。
 *
 * <p>驗兩件事:訊息會被落庫並 ack;**同一則訊息送兩次只會產生一筆訂單**。
 *
 * <p>第二件事是 D4 的核心。「回補或落庫成功、但 ack 之前崩潰」這個窗口消不掉 ——
 * 訊息會留在 pending 被重新領取,於是同一筆預扣被處理第二次。
 * 解法不是把窗口縮小,而是讓重複落庫無害:冪等鍵的唯一約束擋下第二筆,
 * 而消費者 MUST 把它視為「先前已成功」而非「落庫失敗」——
 * **判斷錯的代價是回補一筆已經賣出去的庫存,也就是超賣。**
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "ticket-rush.redis.stream=orders-consumer")
class OrderPersistenceConsumerTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Autowired
    private OrderStreamPort orderStreamPort;

    @Autowired
    private OrderPersistenceConsumer consumer;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StringRedisTemplate redis;

    @Value("${ticket-rush.redis.stream}")
    private String streamKey;

    @Value("${ticket-rush.redis.consumer-group}")
    private String consumerGroup;

    private long eventId;

    @BeforeEach
    void setUp() {
        eventId = givenEvent();
    }

    @Test
    @DisplayName("訊息被落庫,且落庫成功後才 ack")
    void persistsAndAcknowledges() {
        String key = "consumer-" + eventId + "-1";

        orderStreamPort.publish(command(key, 2));

        await(() -> orderCountByKey(key) == 1);
        assertThat(quantityByKey(key)).isEqualTo(2);
        // pending 歸零代表確實 ack 了。若用 receiveAutoAck，這個斷言在落庫失敗時也會通過——
        // 那時 pending 清單就成了裝飾品，對帳永遠不可能收斂。
        await(() -> pendingCount() == 0);
    }

    @Test
    @DisplayName("同一則訊息送兩次只產生一筆訂單 —— 冪等鍵擋下重複落庫")
    void duplicateMessageDoesNotCreateSecondOrder() {
        String key = "consumer-" + eventId + "-dup";
        long before = consumer.duplicatesBlocked();

        orderStreamPort.publish(command(key, 1));
        await(() -> orderCountByKey(key) == 1);

        // 重送：模擬「落庫成功但 ack 之前崩潰」後訊息被重新領取。
        orderStreamPort.publish(command(key, 1));

        await(() -> consumer.duplicatesBlocked() > before);
        assertThat(orderCountByKey(key)).as("重複的訊息不得產生第二筆訂單").isEqualTo(1);
        await(() -> pendingCount() == 0);
    }

    private PurchaseTicketCommand command(String idempotencyKey, int quantity) {
        return new PurchaseTicketCommand(
                new EventId(eventId), new UserId(1L), new Quantity(quantity), new IdempotencyKey(idempotencyKey));
    }

    /**
     * 輪詢等待條件成立。
     *
     * <p><b>刻意不用固定 sleep。</b> 固定等待在快的機器上浪費時間,在慢的機器上變成偶發失敗 ——
     * 而偶發失敗看起來像不穩定的測試,不像等待時間不夠。
     */
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

    private long pendingCount() {
        var summary = redis.opsForStream().pending(streamKey, consumerGroup);
        return summary == null ? 0 : summary.getTotalPendingMessages();
    }

    private int orderCountByKey(String idempotencyKey) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM purchase_order WHERE idempotency_key = ?", Integer.class, idempotencyKey);
    }

    private int quantityByKey(String idempotencyKey) {
        return jdbc.queryForObject(
                "SELECT quantity FROM purchase_order WHERE idempotency_key = ?", Integer.class, idempotencyKey);
    }

    /** 每個測試用新的場次,不 TRUNCATE —— 消費者是非同步的,清空資料表會與它的寫入互相踩踏。 */
    private long givenEvent() {
        return jdbc.queryForObject(
                """
                INSERT INTO event (name, sales_start_at, total_quantity)
                VALUES (?, ?, ?) RETURNING id
                """, Long.class, "消費者測試場次", java.sql.Timestamp.from(Instant.parse("2026-12-01T12:00:00Z")), 500);
    }
}
