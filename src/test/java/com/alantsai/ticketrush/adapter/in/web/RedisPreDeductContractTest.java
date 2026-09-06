package com.alantsai.ticketrush.adapter.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.alantsai.ticketrush.adapter.out.redis.RedisKeys;
import com.alantsai.ticketrush.domain.valueobject.EventId;
import com.alantsai.ticketrush.testsupport.TestcontainersConfiguration;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 第 3 層的回應契約:**202 Accepted,且不提供 {@code orderId}**。
 *
 * <p>這是四層中唯一形狀不同的回應,而**那個差異是流程本質造成的,不是實作缺陷** ——
 * 非同步落庫的回應時機早於訂單建立,任何宣稱訂單已建立的回應都是謊報。
 *
 * <p>原本的 spec 要求「所有策略的成功回應形狀完全相同」,該判準已於本支更正為
 * 「**同步策略之間**形狀完全相同」。更正的理由寫在 {@code api-ticket-purchase} 的 spec 裡:
 * 不得暴露的是**策略身分**,而非流程本質的差異 —— 後者是契約的一部分,前者才是洩漏。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(
        properties = {
            "ticket-rush.strategy=redisPreDeduct",
            // 每個測試 context 用自己的 stream。消費者在所有 context 都會啟動，
            // 共用一條 stream 會讓某個測試投遞的訊息被另一個測試的消費者處理掉——
            // 症狀是偶發的、且只在測試順序改變時才出現。
            "ticket-rush.redis.stream=orders-contract",
            // 對帳關閉（間隔設得極長）。本測試刻意讓 Redis 庫存少於資料庫配額
            // （例如庫存不足的案例設 1 張），那在對帳眼中就是一筆巨大的差額，
            // 它會「好心地」把庫存補回去，於是庫存不足的案例再也拒絕不了。
            "ticket-rush.redis.reconciliation-interval-ms=3600000"
        })
class RedisPreDeductContractTest {

    private static final String KEY = "redis-contract-1";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StringRedisTemplate redis;

    /**
     * <b>刻意不 TRUNCATE。</b> 消費者是非同步的:清空資料表時它可能正在寫入前一個測試的訂單,
     * 於是撞上外鍵違反 —— 那則訊息會永遠留在 pending。
     * 本身不會讓測試變紅,但它把 pending 汙染成一個無法解讀的數字,
     * 而後續的對帳驗收正是以 pending 為判準。
     *
     * <p>改為每個測試用新的場次。快取的 key 仍需清理 —— 它們不受外鍵約束保護。
     */
    @BeforeEach
    @AfterEach
    void clearCacheKeys() {
        Set<String> keys = redis.keys("stock:*");
        keys.addAll(redis.keys("purchased:*"));
        if (!keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @Test
    @DisplayName("預扣成功回 202 Accepted,不含 orderId,改回冪等鍵")
    void acceptedWithoutOrderId() throws Exception {
        long eventId = givenEventOnSale(500);

        mockMvc.perform(post("/api/events/{id}/purchase", eventId)
                        .header("X-User-Id", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(2, KEY)))
                // 202 的語意正是「已受理、尚未完成」。回 201 Created 會是謊報：
                // 客戶端據此立即查詢會得到查無此單，那比契約不一致更糟。
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.success").value(true))
                // orderId 不出現，而不是出現一個 null——沒有值的欄位不出現，比出現 null 更接近事實。
                .andExpect(jsonPath("$.data.orderId").doesNotExist())
                .andExpect(jsonPath("$.data.idempotencyKey").value(KEY))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.quantity").value(2));
    }

    @Test
    @DisplayName("回應不含任何策略識別資訊")
    void responseDoesNotLeakStrategyIdentity() throws Exception {
        long eventId = givenEventOnSale(500);

        // 不得暴露的是「現在跑哪一層」；狀態碼反映的是「訂單建立了沒」，那是流程事實。
        mockMvc.perform(post("/api/events/{id}/purchase", eventId)
                        .header("X-User-Id", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(1, KEY)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.strategy").doesNotExist())
                .andExpect(jsonPath("$.strategy").doesNotExist());
    }

    @Test
    @DisplayName("場次存在但未載入快取庫存時回 409 EVENT_NOT_ON_SALE")
    void notOnSaleWhenCacheStockMissing() throws Exception {
        long eventId = givenEventWithoutCacheStock();

        mockMvc.perform(post("/api/events/{id}/purchase", eventId)
                        .header("X-User-Id", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(1, KEY)))
                .andExpect(status().isConflict())
                // 說成「找不到」會讓使用者以為連結壞了；說成「賣完了」會讓他放棄一場還沒開賣的活動。
                .andExpect(jsonPath("$.code").value("EVENT_NOT_ON_SALE"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("超過限購時回 409 PURCHASE_LIMIT_EXCEEDED")
    void limitExceeded() throws Exception {
        long eventId = givenEventOnSale(500);

        mockMvc.perform(post("/api/events/{id}/purchase", eventId)
                        .header("X-User-Id", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(5, KEY)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PURCHASE_LIMIT_EXCEEDED"));
    }

    @Test
    @DisplayName("快取庫存不足時回 409 INSUFFICIENT_STOCK")
    void insufficientStock() throws Exception {
        long eventId = givenEventOnSale(1);

        mockMvc.perform(post("/api/events/{id}/purchase", eventId)
                        .header("X-User-Id", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(3, KEY)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));
    }

    private String body(int quantity, String key) {
        return """
                {"quantity": %d, "idempotencyKey": "%s"}
                """.formatted(quantity, key);
    }

    private long givenEventWithoutCacheStock() {
        Long eventId = jdbc.queryForObject(
                """
                INSERT INTO event (name, sales_start_at, total_quantity)
                VALUES (?, ?, ?) RETURNING id
                """, Long.class, "尚未開賣的場次", java.sql.Timestamp.from(Instant.parse("2026-12-01T12:00:00Z")), 500);
        jdbc.update("INSERT INTO stock (event_id, available) VALUES (?, ?)", eventId, 500);
        return eventId;
    }

    /** 場次存在於資料庫,且快取庫存已載入 —— 「開賣」在本層是一個明確的動作。 */
    private long givenEventOnSale(int available) {
        long eventId = givenEventWithoutCacheStock();
        redis.opsForValue().set(RedisKeys.stock(new EventId(eventId)), String.valueOf(available));
        return eventId;
    }
}
