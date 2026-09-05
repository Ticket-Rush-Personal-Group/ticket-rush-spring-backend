package com.alantsai.ticketrush.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.alantsai.ticketrush.testsupport.TestcontainersConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * 悲觀鎖策略的併發正確性**驗收**測試。
 *
 * <p><b>本測試進入預設的 {@code verify},不以 tag 隔離。</b> 這與第 0 層的證據測試性質相反:
 * 那些是刻意呈現錯誤行為的證據,這些是必須永遠為綠的驗收 —— 任何回歸都要立刻擋下 CI。
 *
 * <p><b>斷言一律使用「恰好等於」而非「不超過」。</b> 後者在系統完全停擺、一張都沒賣出的情況下
 * 同樣會通過,那是綠燈掩蓋停擺的典型形式。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "ticket-rush.strategy=pessimistic")
class PessimisticLockCorrectnessTest {

    private static final int STOCK = 500;
    private static final int CONCURRENT_REQUESTS = 1000;

    @Value("${local.server.port}")
    private int port;

    @Value("${ticket-rush.max-tickets-per-user}")
    private int limit;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("1000 併發搶 500 張:售出恰好 500、庫存恰好 0,零超賣")
    void noOversellingUnderConcurrency() throws Exception {
        long eventId = givenEvent(STOCK);

        Result result = fireConcurrently(CONCURRENT_REQUESTS, eventId, index -> index + 1L);

        int sold = jdbc.queryForObject("SELECT COALESCE(SUM(quantity), 0) FROM purchase_order", Integer.class);
        int remaining = jdbc.queryForObject("SELECT available FROM stock WHERE event_id = ?", Integer.class, eventId);

        assertThat(sold).as("售出張數應恰好等於初始庫存 —— 用「恰好」而非「不超過」").isEqualTo(STOCK);
        assertThat(remaining).as("最終庫存應恰好為 0").isZero();
        assertThat(result.accepted()).as("成功的請求數應恰好等於庫存量").isEqualTo(STOCK);
        assertThat(result.rejected()).as("其餘請求應全部被拒(庫存不足)").isEqualTo(CONCURRENT_REQUESTS - STOCK);
    }

    @Test
    @DisplayName("同一人 20 個併發請求:成交恰好等於限購上限,零超買")
    void noLimitBreachUnderConcurrency() throws Exception {
        long eventId = givenEvent(1000);
        int requests = 20;

        fireConcurrently(requests, eventId, index -> 42L);

        int purchased = jdbc.queryForObject(
                "SELECT COALESCE(SUM(quantity), 0) FROM purchase_order WHERE user_id = ?", Integer.class, 42L);

        assertThat(purchased).as("單一使用者的成交張數應恰好等於限購上限 —— 第 0 層在此超買 6 張").isEqualTo(limit);
    }

    private record Result(int accepted, int rejected) {}

    private Result fireConcurrently(int requests, long eventId, java.util.function.IntFunction<Long> userIdOf)
            throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        CountDownLatch ready = new CountDownLatch(requests);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < requests; i++) {
                final int index = i;
                pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    HttpResponse<String> response = client.send(
                            purchaseRequest(eventId, index, userIdOf.apply(index)),
                            HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() == 201) {
                        accepted.incrementAndGet();
                    } else {
                        rejected.incrementAndGet();
                    }
                    return null;
                });
            }
            ready.await();
            start.countDown();
        }
        return new Result(accepted.get(), rejected.get());
    }

    private HttpRequest purchaseRequest(long eventId, int index, long userId) {
        return HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:%d/api/events/%d/purchase".formatted(port, eventId)))
                .header("Content-Type", "application/json")
                .header("X-User-Id", String.valueOf(userId))
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"quantity": 1, "idempotencyKey": "pessimistic-%d"}
                        """.formatted(index)))
                .build();
    }

    private long givenEvent(int stock) {
        jdbc.execute("TRUNCATE purchase_order, stock, event RESTART IDENTITY CASCADE");
        Long eventId = jdbc.queryForObject(
                """
                INSERT INTO event (name, sales_start_at, total_quantity)
                VALUES (?, ?, ?) RETURNING id
                """, Long.class, "悲觀鎖驗收場次", java.sql.Timestamp.from(Instant.parse("2026-12-01T12:00:00Z")), stock);
        jdbc.update("INSERT INTO stock (event_id, available) VALUES (?, ?)", eventId, stock);
        return eventId;
    }
}
