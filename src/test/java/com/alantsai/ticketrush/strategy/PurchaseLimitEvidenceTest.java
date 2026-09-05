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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 第 0 層無鎖對照組的**第二組證據:限購同樣被突破**。
 *
 * <p>成因與超賣完全相同 —— 檢查「這個人已經買了幾張」需要先讀取,兩個併發請求讀到相同的已購數、
 * 各自通過檢查。同一個 lost update 模式在 service 內出現兩次,因此本層有兩個缺陷而非一個。
 *
 * <p><b>這組證據比超賣更有說服力,因為它更難被發現。</b> 庫存超賣會讓總量對不上,任何對帳都會抓到;
 * 而「某個人多買了兩張」不會讓任何總量出錯 —— 除非專門去查那個人。實務上這類缺陷可以存活很久,
 * 直到有人拿它套利。
 *
 * <p>與超賣證據共用 {@code @Tag("overselling-evidence")}:兩者是同一個問題的兩種表現,
 * 分開的 tag 會讓「執行所有證據測試」需要記兩個名字。
 *
 * <p>單獨執行:{@code ./mvnw test -Dsurefire.excludedGroups= -Dgroups=overselling-evidence}
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@Tag("overselling-evidence")
class PurchaseLimitEvidenceTest {

    /** 庫存刻意設得充足:若庫存不足,失敗原因會混入「沒票了」,證據就不乾淨了。 */
    private static final int ABUNDANT_STOCK = 1000;

    private static final int CONCURRENT_REQUESTS = 20;
    private static final long SINGLE_USER = 42L;

    @Value("${local.server.port}")
    private int port;

    @Value("${ticket-rush.max-tickets-per-user}")
    private int limit;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("同一人併發下單突破限購上限")
    void purchaseLimitIsBreachedUnderConcurrency() throws Exception {
        long eventId = givenEventWithAbundantStock();

        HttpClient client = HttpClient.newHttpClient();
        CountDownLatch ready = new CountDownLatch(CONCURRENT_REQUESTS);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger limitRejected = new AtomicInteger();
        AtomicInteger otherRejected = new AtomicInteger();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
                final int index = i;
                pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    HttpResponse<String> response =
                            client.send(purchaseRequest(eventId, index), HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() == 201) {
                        accepted.incrementAndGet();
                    } else if (response.body().contains("PURCHASE_LIMIT_EXCEEDED")) {
                        limitRejected.incrementAndGet();
                    } else {
                        otherRejected.incrementAndGet();
                    }
                    return null;
                });
            }
            ready.await();
            start.countDown();
        }

        int purchased = jdbc.queryForObject(
                "SELECT COALESCE(SUM(quantity), 0) FROM purchase_order WHERE event_id = ? AND user_id = ?",
                Integer.class,
                eventId,
                SINGLE_USER);
        int overLimit = purchased - limit;

        System.out.printf(
                """

                ===== 無鎖對照組:限購突破證據 =====
                併發請求數        : %d(單一使用者,各買 1 張)
                限購上限          : %d 張
                成功建立訂單      : %d 筆
                被限購擋下        : %d 筆
                其他原因被拒      : %d 筆
                該使用者實際成交  : %d 張
                >>> 超買張數      : %d
                ===================================
                庫存刻意設為 %d(充足),避免失敗原因混入「沒票了」。
                成因與超賣相同:讀取已購數 → 判斷 → 寫入,期間沒有任何互斥。
                %n""",
                CONCURRENT_REQUESTS,
                limit,
                accepted.get(),
                limitRejected.get(),
                otherRejected.get(),
                purchased,
                overLimit,
                ABUNDANT_STOCK);

        assertThat(purchased).as("該使用者的成交張數應超過限購上限 —— 這就是限購被突破").isGreaterThan(limit);

        assertThat(limitRejected.get())
                .as("被限購擋下的請求數應遠少於「請求數減上限」—— 多數請求根本沒被擋到")
                .isLessThan(CONCURRENT_REQUESTS - limit);
    }

    private HttpRequest purchaseRequest(long eventId, int index) {
        return HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:%d/api/events/%d/purchase".formatted(port, eventId)))
                .header("Content-Type", "application/json")
                .header("X-User-Id", String.valueOf(SINGLE_USER))
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"quantity": 1, "idempotencyKey": "limit-evidence-%d"}
                        """.formatted(index)))
                .build();
    }

    private long givenEventWithAbundantStock() {
        jdbc.execute("TRUNCATE purchase_order, stock, event RESTART IDENTITY CASCADE");
        Long eventId = jdbc.queryForObject(
                """
                INSERT INTO event (name, sales_start_at, total_quantity)
                VALUES (?, ?, ?) RETURNING id
                """,
                Long.class,
                "限購證據場次",
                java.sql.Timestamp.from(Instant.parse("2026-12-01T12:00:00Z")),
                ABUNDANT_STOCK);
        jdbc.update("INSERT INTO stock (event_id, available) VALUES (?, ?)", eventId, ABUNDANT_STOCK);
        return eventId;
    }
}
