package com.alantsai.ticketrush.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.alantsai.ticketrush.testsupport.TestcontainersConfiguration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 單人限購的契約測試,涵蓋 {@code api-ticket-purchase} 的限購 scenario。
 *
 * <p>上限取自設定,預設 4 張。測試不覆寫該設定 —— 若日後預設值變更而測試沒跟著調整,
 * 這些測試會失敗,那正是我們想要的:**設定與預期不一致時要有人知道。**
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PurchaseLimitContractTest {

    private static final int LIMIT = 4;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void cleanup() {
        jdbc.execute("TRUNCATE purchase_order, stock, event RESTART IDENTITY CASCADE");
    }

    private long givenEventWithStock(String name, int available) {
        Long eventId = jdbc.queryForObject(
                """
                INSERT INTO event (name, sales_start_at, total_quantity)
                VALUES (?, ?, ?) RETURNING id
                """,
                Long.class,
                name,
                java.sql.Timestamp.from(Instant.parse("2026-12-01T12:00:00Z")),
                Math.max(available, 1));
        jdbc.update("INSERT INTO stock (event_id, available) VALUES (?, ?)", eventId, available);
        return eventId;
    }

    private void givenExistingOrder(long eventId, long userId, int quantity, String key) {
        jdbc.update("""
                INSERT INTO purchase_order (event_id, user_id, quantity, status, idempotency_key)
                VALUES (?, ?, ?, 'PENDING', ?)
                """, eventId, userId, quantity, key);
    }

    private String body(int quantity, String key) {
        return """
                {"quantity": %d, "idempotencyKey": "%s"}
                """.formatted(quantity, key);
    }

    private int stockOf(long eventId) {
        return jdbc.queryForObject("SELECT available FROM stock WHERE event_id = ?", Integer.class, eventId);
    }

    @Test
    @DisplayName("累計未超過上限時購票成功")
    void allowsWhenWithinLimit() throws Exception {
        long eventId = givenEventWithStock("測試場次", 500);
        givenExistingOrder(eventId, 1L, 2, "existing");

        mockMvc.perform(post("/api/events/{id}/purchase", eventId)
                        .header("X-User-Id", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(2, "new-key")))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("累計超過上限時回 409 PURCHASE_LIMIT_EXCEEDED,且庫存不變")
    void rejectsWhenExceedingLimit() throws Exception {
        long eventId = givenEventWithStock("測試場次", 500);
        givenExistingOrder(eventId, 1L, 3, "existing");

        mockMvc.perform(post("/api/events/{id}/purchase", eventId)
                        .header("X-User-Id", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(2, "new-key")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PURCHASE_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.data").doesNotExist());

        // 只檢查狀態碼會漏掉「拒絕了但庫存已扣」——限購在庫存之前，被擋下的請求不該碰庫存
        assertThat(stockOf(eventId)).isEqualTo(500);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM purchase_order", Long.class))
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("單次請求即超過上限")
    void rejectsWhenSingleRequestExceedsLimit() throws Exception {
        long eventId = givenEventWithStock("測試場次", 500);

        mockMvc.perform(post("/api/events/{id}/purchase", eventId)
                        .header("X-User-Id", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(LIMIT + 1, "key")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PURCHASE_LIMIT_EXCEEDED"));
    }

    @Test
    @DisplayName("限購以張數計算,不是訂單筆數")
    void limitCountsTicketsNotOrders() throws Exception {
        long eventId = givenEventWithStock("測試場次", 500);
        // 兩筆訂單、合計 4 張。若以訂單筆數計算（2 筆 < 4）會誤放行。
        givenExistingOrder(eventId, 1L, 1, "k1");
        givenExistingOrder(eventId, 1L, 3, "k2");

        mockMvc.perform(post("/api/events/{id}/purchase", eventId)
                        .header("X-User-Id", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(1, "k3")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PURCHASE_LIMIT_EXCEEDED"));
    }

    @Test
    @DisplayName("限購為每場次獨立,不是每人全站")
    void limitIsPerEvent() throws Exception {
        long eventA = givenEventWithStock("場次 A", 500);
        long eventB = givenEventWithStock("場次 B", 500);
        givenExistingOrder(eventA, 1L, LIMIT, "k-a");

        mockMvc.perform(post("/api/events/{id}/purchase", eventB)
                        .header("X-User-Id", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(1, "k-b")))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("同時超限且庫存不足時回 PURCHASE_LIMIT_EXCEEDED —— 檢查順序的驗證")
    void limitCheckPrecedesStockCheck() throws Exception {
        long eventId = givenEventWithStock("測試場次", 1);
        givenExistingOrder(eventId, 1L, LIMIT, "existing");

        mockMvc.perform(post("/api/events/{id}/purchase", eventId)
                        .header("X-User-Id", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(3, "new-key")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PURCHASE_LIMIT_EXCEEDED"));
    }

    @Test
    @DisplayName("不同使用者的限購互不影響")
    void limitIsPerUser() throws Exception {
        long eventId = givenEventWithStock("測試場次", 500);
        givenExistingOrder(eventId, 1L, LIMIT, "k-u1");

        mockMvc.perform(post("/api/events/{id}/purchase", eventId)
                        .header("X-User-Id", 2L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(1, "k-u2")))
                .andExpect(status().isCreated());
    }
}
