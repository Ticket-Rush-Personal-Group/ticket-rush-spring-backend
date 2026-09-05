package com.alantsai.ticketrush.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.alantsai.ticketrush.testsupport.TestcontainersConfiguration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Flyway migration 與表結構的整合測試。
 *
 * <p>驗證的是**真實 PostgreSQL 上的實際行為**,不是 migration 檔案的文字內容 ——
 * 約束寫對了但沒生效(例如寫成註解、或被後續 migration 覆蓋)是文字比對抓不到的。
 *
 * <p>不使用 {@code @Transactional} 自動回滾:違反 CHECK 或 UNIQUE 會使 PostgreSQL 的交易進入
 * aborted 狀態,同一交易內的後續語句全部失敗。改為每個測試後以 TRUNCATE 清理。
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SchemaMigrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void cleanup() {
        jdbc.execute("TRUNCATE purchase_order, stock, event RESTART IDENTITY CASCADE");
    }

    private long insertEvent() {
        return jdbc.queryForObject(
                """
                INSERT INTO event (name, sales_start_at, total_quantity)
                VALUES (?, ?, ?) RETURNING id
                """, Long.class, "測試場次", java.sql.Timestamp.from(Instant.parse("2026-12-01T12:00:00Z")), 500);
    }

    @Test
    @DisplayName("migration 套用後三張表都存在")
    void allTablesExist() {
        var tables = jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name IN ('event', 'stock', 'purchase_order')
                """, String.class);

        assertThat(tables).containsExactlyInAnyOrder("event", "stock", "purchase_order");
    }

    @Test
    @DisplayName("庫存扣成負值被 CHECK 約束拒絕")
    void negativeStockIsRejected() {
        long eventId = insertEvent();
        jdbc.update("INSERT INTO stock (event_id, available) VALUES (?, ?)", eventId, 3);

        assertThatThrownBy(() -> jdbc.update("UPDATE stock SET available = available - 5 WHERE event_id = ?", eventId))
                .hasMessageContaining("ck_stock_available_non_negative");
    }

    @Test
    @DisplayName("相同 idempotency_key 的第二筆訂單被唯一約束拒絕")
    void duplicateIdempotencyKeyIsRejected() {
        long eventId = insertEvent();
        String key = "same-key-001";

        jdbc.update("""
                INSERT INTO purchase_order (event_id, user_id, quantity, status, idempotency_key)
                VALUES (?, ?, ?, ?, ?)
                """, eventId, 1L, 2, "PENDING", key);

        assertThatThrownBy(() -> jdbc.update("""
                        INSERT INTO purchase_order (event_id, user_id, quantity, status, idempotency_key)
                        VALUES (?, ?, ?, ?, ?)
                        """, eventId, 2L, 1, "PENDING", key))
                .hasMessageContaining("uq_purchase_order_idempotency_key");
    }

    @Test
    @DisplayName("訂單表名不是保留字,原生 SQL 無需加引號")
    void orderTableNameNeedsNoQuoting() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM purchase_order", Long.class);

        assertThat(count).isZero();
    }
}
