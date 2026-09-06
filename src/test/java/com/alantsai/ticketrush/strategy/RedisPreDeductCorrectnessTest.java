package com.alantsai.ticketrush.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.alantsai.ticketrush.adapter.out.redis.RedisKeys;
import com.alantsai.ticketrush.application.port.out.OrderStreamPort;
import com.alantsai.ticketrush.application.port.out.StockCachePort;
import com.alantsai.ticketrush.domain.valueobject.EventId;
import com.alantsai.ticketrush.testsupport.TestcontainersConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
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
 * Redis 預扣策略的併發正確性**驗收**測試。
 *
 * <p><b>判準與前三層完全相同,不因為「最終一致」而放寬。</b> Redis 已扣但尚未落庫的部分
 * 不算超賣 —— 但**最終**資料庫的訂單張數必須恰好等於初始配額。
 * 「最終一致」是關於**何時**一致,不是關於**是否**一致。
 *
 * <p><b>等待條件是「積壓為空且訂單數穩定」,不是固定 sleep。</b> 固定等待在快的機器上浪費時間,
 * 在慢的機器上變成偶發失敗 —— 而偶發失敗看起來像不穩定的測試,不像等待時間不夠。
 *
 * <p>對帳排程以 200ms 的間隔真的跑著:本測試同時驗證**它在正常情況下不會亂補**。
 * 一個只在故障情境測試的對帳,很可能平常就在默默破壞資料。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(
        properties = {
            "ticket-rush.strategy=redisPreDeduct",
            "ticket-rush.redis.stream=orders-acceptance",
            "ticket-rush.redis.reconciliation-interval-ms=200"
        })
class RedisPreDeductCorrectnessTest {

    private static final int STOCK = 500;
    private static final int CONCURRENT_REQUESTS = 1000;
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Value("${local.server.port}")
    private int port;

    @Value("${ticket-rush.max-tickets-per-user}")
    private int limit;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private StockCachePort stockCachePort;

    @Autowired
    private OrderStreamPort orderStreamPort;

    @Test
    @DisplayName("1000 併發搶 500 張:資料庫訂單恰好 500、快取餘量恰好 0,零超賣")
    void noOversellingUnderConcurrency() throws Exception {
        EventId event = givenEventOnSale(STOCK);

        Result result = fireConcurrently(CONCURRENT_REQUESTS, event, index -> index + 1L);

        assertThat(result.accepted()).as("受理的請求數應恰好等於配額").isEqualTo(STOCK);
        assertThat(stockCachePort.available(event)).as("快取餘量應恰好為 0").hasValue(0);

        // 非同步落庫：等訊息全部處理完（積壓為空）才斷言資料庫。
        awaitSettled(event);

        int sold = soldOf(event);
        assertThat(sold).as("資料庫訂單張數應恰好等於配額 —— 判準與前三層相同,不因最終一致而放寬").isEqualTo(STOCK);
    }

    @Test
    @DisplayName("同一人 20 個併發請求:成交恰好等於限購上限,零超買")
    void noLimitBreachUnderConcurrency() throws Exception {
        EventId event = givenEventOnSale(1000);

        fireConcurrently(20, event, index -> 42L);
        awaitSettled(event);

        int purchased = jdbc.queryForObject(
                "SELECT COALESCE(SUM(quantity), 0) FROM purchase_order WHERE event_id = ? AND user_id = ?",
                Integer.class,
                event.value(),
                42L);

        // 限購在 Lua 腳本內原子完成。移到應用端就是 check-then-act——
        // 而實測顯示那在 Redis 上比在資料庫上失效得更徹底：200 個併發全部成功。
        assertThat(purchased).as("單一使用者的成交張數應恰好等於限購上限").isEqualTo(limit);
    }

    @Test
    @DisplayName("正常流程下對帳不會亂補 —— 排程全程跑著,快取餘量仍為 0")
    void reconciliationDoesNotRestoreDuringNormalOperation() throws Exception {
        EventId event = givenEventOnSale(STOCK);

        fireConcurrently(CONCURRENT_REQUESTS, event, index -> index + 1L);
        awaitSettled(event);

        // 對帳排程以 200ms 的間隔跑了整場。若它在積壓非空時就回補，
        // 餘量會被墊高、而那些訊息稍後照樣落庫——**那就是超賣**。
        assertThat(stockCachePort.available(event)).as("對帳不得在正常流程中補回任何庫存").hasValue(0);
        assertThat(soldOf(event)).isEqualTo(STOCK);
    }

    private record Result(int accepted, int rejected) {}

    /** 等到訊息全部處理完畢:積壓為空,且訂單數不再變動。 */
    private void awaitSettled(EventId event) {
        await(() -> orderStreamPort.backlog().isEmpty());
        int[] previous = {-1};
        await(() -> {
            int current = soldOf(event);
            boolean stable = current == previous[0];
            previous[0] = current;
            return stable && orderStreamPort.backlog().isEmpty();
        });
    }

    private void await(BooleanSupplier condition) {
        Instant deadline = Instant.now().plus(TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待被中斷", e);
            }
        }
        throw new AssertionError("等待逾時(%s):條件未成立".formatted(TIMEOUT));
    }

    private int soldOf(EventId event) {
        return jdbc.queryForObject(
                "SELECT COALESCE(SUM(quantity), 0) FROM purchase_order WHERE event_id = ?",
                Integer.class,
                event.value());
    }

    private Result fireConcurrently(int requests, EventId event, java.util.function.IntFunction<Long> userIdOf)
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
                            purchaseRequest(event, index, userIdOf.apply(index)), HttpResponse.BodyHandlers.ofString());
                    // 本層成功的狀態碼是 202 Accepted，不是 201——
                    // 回應的當下訂單確實還不存在。
                    if (response.statusCode() == 202) {
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

    private HttpRequest purchaseRequest(EventId event, int index, long userId) {
        return HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:%d/api/events/%d/purchase".formatted(port, event.value())))
                .header("Content-Type", "application/json")
                .header("X-User-Id", String.valueOf(userId))
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"quantity": 1, "idempotencyKey": "redis-%d-%d"}
                        """.formatted(event.value(), index)))
                .build();
    }

    /**
     * 場次、其配額,以及快取庫存。
     *
     * <p><b>資料庫的 {@code stock.available} 與快取的初始值必須一致。</b> 第 3 層不扣減前者,
     * 它保留為配額 —— 而對帳正是以「配額 − 快取餘量」推算已預扣的張數。兩者不一致,
     * 對帳從第一秒就是錯的。
     */
    private EventId givenEventOnSale(int allocation) {
        Long eventId = jdbc.queryForObject(
                """
                INSERT INTO event (name, sales_start_at, total_quantity)
                VALUES (?, ?, ?) RETURNING id
                """,
                Long.class,
                "Redis 預扣驗收場次",
                java.sql.Timestamp.from(Instant.parse("2026-12-01T12:00:00Z")),
                allocation);
        jdbc.update("INSERT INTO stock (event_id, available) VALUES (?, ?)", eventId, allocation);
        redis.opsForValue().set(RedisKeys.stock(new EventId(eventId)), String.valueOf(allocation));
        return new EventId(eventId);
    }
}
