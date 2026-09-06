package com.alantsai.ticketrush.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.alantsai.ticketrush.application.metrics.RetryStatistics;
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
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * 樂觀鎖策略的併發正確性**驗收**測試。
 *
 * <p><b>本測試進入預設的 {@code verify},不以 tag 隔離</b> —— 與第 1 層相同,它是驗收測試,
 * 任何回歸都要立刻擋下 CI。
 *
 * <p><b>斷言一律使用「恰好等於」而非「不超過」。</b> 後者在系統完全停擺、一張都沒賣出的情況下
 * 同樣會通過。
 *
 * <p><b>第三個斷言是本層特有的:重試次數分佈的最大值必須大於 1。</b> 若全部一次成功,代表這個測試
 * 根本沒有製造出競爭 —— 那種綠燈毫無意義,而且它跟真的通過長得一模一樣。
 * 樂觀鎖的正確性只有在真的發生衝突時才被驗證到。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "ticket-rush.strategy=optimistic")
class OptimisticLockCorrectnessTest {

    private static final int STOCK = 500;
    private static final int CONCURRENT_REQUESTS = 1000;

    @Value("${local.server.port}")
    private int port;

    @Value("${ticket-rush.max-tickets-per-user}")
    private int limit;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RetryStatistics retryStatistics;

    @BeforeEach
    void resetStatistics() {
        // test context 快取讓同一個實例跨測試方法存活，前一個測試的數字會汙染後一個的斷言。
        retryStatistics.reset();
    }

    @Test
    @DisplayName("1000 併發搶 500 張:售出恰好 500、庫存恰好 0,零超賣")
    void noOversellingUnderConcurrency() throws Exception {
        long eventId = givenEvent(STOCK);

        Result result = fireConcurrently(CONCURRENT_REQUESTS, eventId, index -> index + 1L);

        int sold = jdbc.queryForObject("SELECT COALESCE(SUM(quantity), 0) FROM purchase_order", Integer.class);
        int remaining = jdbc.queryForObject("SELECT available FROM stock WHERE event_id = ?", Integer.class, eventId);

        assertThat(sold).as("售出張數應恰好等於初始庫存 —— %s", diagnostics()).isEqualTo(STOCK);
        assertThat(remaining).as("最終庫存應恰好為 0").isZero();
        assertThat(sold).as("售出張數應等於庫存實際減少量 —— 不等則代表超賣").isEqualTo(STOCK - remaining);
        assertThat(result.accepted()).as("成功的請求數應恰好等於庫存量").isEqualTo(STOCK);

        assertThat(retryStatistics.maxObservedAttempts())
                .as("重試次數的最大值必須大於 1 —— 全部一次成功代表這個測試沒有製造出競爭,那種綠燈毫無意義")
                .isGreaterThan(1);
    }

    @Test
    @DisplayName("同一人 20 個併發請求:成交恰好等於限購上限,零超買")
    void noLimitBreachUnderConcurrency() throws Exception {
        long eventId = givenEvent(1000);
        int requests = 20;

        fireConcurrently(requests, eventId, index -> 42L);

        int purchased = jdbc.queryForObject(
                "SELECT COALESCE(SUM(quantity), 0) FROM purchase_order WHERE user_id = ?", Integer.class, 42L);

        // 版本號只保護 stock 那一列，限購讀的是 purchase_order 的聚合——不在版本的保護範圍內。
        // 這一條之所以成立，靠的是「先讀版本、再讀已購數」的順序，以及 CAS 讓成交序列化。
        assertThat(purchased).as("單一使用者的成交張數應恰好等於限購上限 —— %s", diagnostics()).isEqualTo(limit);
    }

    /** 失敗時把統計一併印出來 —— 只說「預期 500 實際 137」無法判斷是超賣、耗盡、還是根本沒跑起來。 */
    private String diagnostics() {
        long[] distribution = retryStatistics.distribution();
        String buckets = IntStream.rangeClosed(1, retryStatistics.maxAttempts())
                .filter(attempts -> distribution[attempts] > 0)
                .mapToObj(attempts -> "%d次:%d".formatted(attempts, distribution[attempts]))
                .collect(Collectors.joining(", "));
        return "重試上限 %d、最大嘗試 %d、平均 %.2f、耗盡 %d、分佈[%s]"
                .formatted(
                        retryStatistics.maxAttempts(),
                        retryStatistics.maxObservedAttempts(),
                        retryStatistics.averageAttempts(),
                        retryStatistics.exhaustedCount(),
                        buckets);
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
                        {"quantity": 1, "idempotencyKey": "optimistic-%d"}
                        """.formatted(index)))
                .build();
    }

    private long givenEvent(int stock) {
        jdbc.execute("TRUNCATE purchase_order, stock, event RESTART IDENTITY CASCADE");
        Long eventId = jdbc.queryForObject(
                """
                INSERT INTO event (name, sales_start_at, total_quantity)
                VALUES (?, ?, ?) RETURNING id
                """, Long.class, "樂觀鎖驗收場次", java.sql.Timestamp.from(Instant.parse("2026-12-01T12:00:00Z")), stock);
        jdbc.update("INSERT INTO stock (event_id, available) VALUES (?, ?)", eventId, stock);
        return eventId;
    }
}
