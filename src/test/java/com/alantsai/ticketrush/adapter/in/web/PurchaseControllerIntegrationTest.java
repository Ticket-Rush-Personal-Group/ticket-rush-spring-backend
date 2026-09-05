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
 * 購票端點的契約測試,涵蓋 {@code api-ticket-purchase} 的全部 scenario。
 *
 * <p>經由真實的 HTTP 路徑與真實的 PostgreSQL 驗證 —— 錯誤處理、Bean Validation、
 * 資料庫約束的映射都只在完整路徑上才會顯現。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PurchaseControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void cleanup() {
        jdbc.execute("TRUNCATE purchase_order, stock, event RESTART IDENTITY CASCADE");
    }

    private long givenEventWithStock(int available) {
        Long eventId = jdbc.queryForObject(
                """
                INSERT INTO event (name, sales_start_at, total_quantity)
                VALUES (?, ?, ?) RETURNING id
                """, Long.class, "測試場次", java.sql.Timestamp.from(Instant.parse("2026-12-01T12:00:00Z")), available);
        jdbc.update("INSERT INTO stock (event_id, available) VALUES (?, ?)", eventId, available);
        return eventId;
    }

    private String body(int quantity, String key) {
        return """
                {"quantity": %d, "idempotencyKey": "%s"}
                """.formatted(quantity, key);
    }

    @Test
    @DisplayName("庫存充足時購票成功,回應 201 與統一 wrapper")
    void purchaseSucceeds() throws Exception {
        long eventId = givenEventWithStock(500);

        mockMvc.perform(post("/api/events/{id}/purchase", eventId)
                        .header("X-User-Id", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(2, "key-001")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.orderId").isNumber())
                .andExpect(jsonPath("$.data.quantity").value(2))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.timestamp").exists());

        Integer remaining =
                jdbc.queryForObject("SELECT available FROM stock WHERE event_id = ?", Integer.class, eventId);
        assertThat(remaining).isEqualTo(498);
    }

    @Test
    @DisplayName("回應不洩漏當前使用的併發策略")
    void responseDoesNotExposeStrategy() throws Exception {
        long eventId = givenEventWithStock(10);

        String response = mockMvc.perform(post("/api/events/{id}/purchase", eventId)
                        .header("X-User-Id", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(1, "key-strategy")))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(response).doesNotContain("noLock").doesNotContain("strategy");
    }

    @Test
    @DisplayName("庫存不足回應 409 INSUFFICIENT_STOCK,且不建立訂單")
    void insufficientStock() throws Exception {
        long eventId = givenEventWithStock(1);

        mockMvc.perform(post("/api/events/{id}/purchase", eventId)
                        .header("X-User-Id", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(5, "key-002")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"))
                .andExpect(jsonPath("$.data").doesNotExist());

        Long orders = jdbc.queryForObject("SELECT count(*) FROM purchase_order", Long.class);
        assertThat(orders).isZero();
        Integer remaining =
                jdbc.queryForObject("SELECT available FROM stock WHERE event_id = ?", Integer.class, eventId);
        assertThat(remaining).isEqualTo(1);
    }

    @Test
    @DisplayName("場次不存在回應 404 EVENT_NOT_FOUND")
    void eventNotFound() throws Exception {
        mockMvc.perform(post("/api/events/{id}/purchase", 99999L)
                        .header("X-User-Id", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(1, "key-003")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_FOUND"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("冪等鍵重複回應 409 DUPLICATE_REQUEST,且不建立第二筆訂單")
    void duplicateIdempotencyKey() throws Exception {
        long eventId = givenEventWithStock(500);
        String key = "key-duplicate";

        mockMvc.perform(post("/api/events/{id}/purchase", eventId)
                        .header("X-User-Id", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(1, key)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/events/{id}/purchase", eventId)
                        .header("X-User-Id", 2L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(1, key)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_REQUEST"))
                .andExpect(jsonPath("$.data").doesNotExist());

        Long orders = jdbc.queryForObject("SELECT count(*) FROM purchase_order", Long.class);
        assertThat(orders).isEqualTo(1L);
    }

    @Test
    @DisplayName("張數為零回應 400 INVALID_REQUEST,且未進入 application service")
    void nonPositiveQuantity() throws Exception {
        long eventId = givenEventWithStock(500);

        mockMvc.perform(post("/api/events/{id}/purchase", eventId)
                        .header("X-User-Id", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(0, "key-004")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.data").doesNotExist());

        Integer remaining =
                jdbc.queryForObject("SELECT available FROM stock WHERE event_id = ?", Integer.class, eventId);
        assertThat(remaining).isEqualTo(500);
    }

    @Test
    @DisplayName("缺少 X-User-Id 回應 400 MISSING_USER_ID")
    void missingUserIdHeader() throws Exception {
        long eventId = givenEventWithStock(500);

        mockMvc.perform(post("/api/events/{id}/purchase", eventId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(1, "key-005")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_USER_ID"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("冪等鍵超過 64 字元回應 400 INVALID_REQUEST")
    void idempotencyKeyTooLong() throws Exception {
        long eventId = givenEventWithStock(500);

        mockMvc.perform(post("/api/events/{id}/purchase", eventId)
                        .header("X-User-Id", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(1, "x".repeat(65))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
}
