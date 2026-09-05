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
 * 第 0 層無鎖對照組的超賣證據。**本測試證明錯誤會發生,不是證明它不會。**
 *
 * <p>這是整個專案的敘事起點:沒有這組數字,後面三層策略的價值無從量化,README 也沒有開場。
 *
 * <p><b>任何人都不得「修好」這個測試。</b> 它以 {@code @Tag("overselling-evidence")} 隔離,
 * 由 surefire 預設排除,不阻斷一般建置。刻意不用 {@code @Disabled} —— 那會顯示為 skipped,
 * 外觀等同壞掉或未完成的測試。
 *
 * <p>單獨執行:{@code ./mvnw test -DexcludedGroups= -Dgroups=overselling-evidence}
 *
 * <p>請求經由**真實的 HTTP 路徑**發出(隨機埠 + JDK HttpClient),而非直接呼叫 application service。
 * 證據要來自使用者實際會走的路徑,否則說服力打折。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@Tag("overselling-evidence")
class OversellingEvidenceTest {

    private static final int INITIAL_STOCK = 500;
    private static final int CONCURRENT_REQUESTS = 1000;

    // 以屬性注入取得埠號,不用 @LocalServerPort —— 屬性名稱不會隨版本搬家。
    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("無鎖策略在 1000 併發下超賣,且庫存不為負(成因是 lost update)")
    void noLockStrategyOversellsUnderConcurrency() throws Exception {
        long eventId = givenEventWithStock();

        HttpClient client = HttpClient.newHttpClient();
        CountDownLatch ready = new CountDownLatch(CONCURRENT_REQUESTS);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        // 虛擬執行緒:1000 個平台執行緒會耗盡測試環境的資源,
        // 而虛擬執行緒正是本專案後續要量測的維度之一。
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
                    } else {
                        rejected.incrementAndGet();
                    }
                    return null;
                });
            }
            ready.await();
            start.countDown();
        }

        int soldTickets = jdbc.queryForObject("SELECT COALESCE(SUM(quantity), 0) FROM purchase_order", Integer.class);
        int remainingStock =
                jdbc.queryForObject("SELECT available FROM stock WHERE event_id = ?", Integer.class, eventId);
        int stockDecrease = INITIAL_STOCK - remainingStock;
        int oversold = soldTickets - stockDecrease;

        System.out.printf(
                """

                ===== 無鎖對照組:超賣證據 =====
                總請求數      : %d
                成功建立訂單  : %d 筆(HTTP 201)
                遭拒          : %d 筆
                初始庫存      : %d
                最終庫存      : %d
                庫存實際減少  : %d
                累計售出張數  : %d
                >>> 超賣張數  : %d
                ================================
                測量條件:單機 JVM、嵌入式 Tomcat、Testcontainers postgres:17、
                         虛擬執行緒發送、未套用資源限制。此為正確性證據,非效能數據。
                %n""",
                CONCURRENT_REQUESTS,
                accepted.get(),
                rejected.get(),
                INITIAL_STOCK,
                remainingStock,
                stockDecrease,
                soldTickets,
                oversold);

        // 1. 超賣確實發生
        assertThat(soldTickets).as("累計售出張數應超過總庫存 —— 這就是超賣").isGreaterThan(INITIAL_STOCK);

        // 2. 成因是 lost update,不是扣成負數
        assertThat(remainingStock)
                .as("庫存不應為負 —— 超賣的成因是 lost update,CHECK 約束攔不到它")
                .isGreaterThanOrEqualTo(0);

        // 3. 售出量與庫存減少量不一致 —— 超賣最精確的表述
        assertThat(soldTickets).as("售出張數與庫存減少量應不相等,兩者差額即為超賣張數").isNotEqualTo(stockDecrease);
    }

    private HttpRequest purchaseRequest(long eventId, int index) {
        return HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:%d/api/events/%d/purchase".formatted(port, eventId)))
                .header("Content-Type", "application/json")
                .header("X-User-Id", String.valueOf(index + 1))
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"quantity": 1, "idempotencyKey": "evidence-%d"}
                        """.formatted(index)))
                .build();
    }

    private long givenEventWithStock() {
        jdbc.execute("TRUNCATE purchase_order, stock, event RESTART IDENTITY CASCADE");
        Long eventId = jdbc.queryForObject(
                """
                INSERT INTO event (name, sales_start_at, total_quantity)
                VALUES (?, ?, ?) RETURNING id
                """,
                Long.class,
                "超賣證據場次",
                java.sql.Timestamp.from(Instant.parse("2026-12-01T12:00:00Z")),
                INITIAL_STOCK);
        jdbc.update("INSERT INTO stock (event_id, available) VALUES (?, ?)", eventId, INITIAL_STOCK);
        return eventId;
    }
}
