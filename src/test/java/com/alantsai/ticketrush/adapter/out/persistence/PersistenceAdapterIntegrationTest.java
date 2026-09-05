package com.alantsai.ticketrush.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.alantsai.ticketrush.application.port.out.LoadEventPort;
import com.alantsai.ticketrush.application.port.out.LoadStockPort;
import com.alantsai.ticketrush.application.port.out.SaveOrderPort;
import com.alantsai.ticketrush.domain.model.Event;
import com.alantsai.ticketrush.domain.model.Order;
import com.alantsai.ticketrush.domain.model.OrderStatus;
import com.alantsai.ticketrush.domain.model.Stock;
import com.alantsai.ticketrush.domain.valueobject.EventId;
import com.alantsai.ticketrush.domain.valueobject.IdempotencyKey;
import com.alantsai.ticketrush.domain.valueobject.Quantity;
import com.alantsai.ticketrush.domain.valueobject.UserId;
import com.alantsai.ticketrush.testsupport.TestcontainersConfiguration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * out port 經 persistence adapter 到真實 PostgreSQL 的完整路徑測試。
 *
 * <p>測試對象是 port 介面而非 repository —— 驗證的是應用層真正會走的那條路,
 * 包含 mapper 的轉換。只測 repository 會漏掉 domain 與 entity 之間的轉換錯誤。
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PersistenceAdapterIntegrationTest {

    @Autowired
    private LoadEventPort loadEventPort;

    @Autowired
    private LoadStockPort loadStockPort;

    @Autowired
    private SaveOrderPort saveOrderPort;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void cleanup() {
        jdbc.execute("TRUNCATE purchase_order, stock, event RESTART IDENTITY CASCADE");
    }

    private long givenEvent(int totalQuantity) {
        return jdbc.queryForObject(
                """
                INSERT INTO event (name, sales_start_at, total_quantity)
                VALUES (?, ?, ?) RETURNING id
                """, Long.class, "測試場次", java.sql.Timestamp.from(Instant.parse("2026-12-01T12:00:00Z")), totalQuantity);
    }

    private void givenStock(long eventId, int available) {
        jdbc.update("INSERT INTO stock (event_id, available) VALUES (?, ?)", eventId, available);
    }

    @Test
    @DisplayName("LoadEventPort 讀得到場次,且欄位經 mapper 正確還原")
    void loadEventReturnsMappedDomainModel() {
        long eventId = givenEvent(500);

        Optional<Event> found = loadEventPort.loadEvent(new EventId(eventId));

        assertThat(found).isPresent();
        assertThat(found.get().name()).isEqualTo("測試場次");
        assertThat(found.get().totalQuantity()).isEqualTo(500);
        assertThat(found.get().salesStartAt()).isEqualTo(Instant.parse("2026-12-01T12:00:00Z"));
    }

    @Test
    @DisplayName("LoadEventPort 對不存在的場次回傳空值")
    void loadEventReturnsEmptyForMissingEvent() {
        assertThat(loadEventPort.loadEvent(new EventId(99999L))).isEmpty();
    }

    @Test
    @DisplayName("LoadStockPort 讀得到庫存,version 預設為 0")
    void loadStockReturnsMappedDomainModel() {
        long eventId = givenEvent(500);
        givenStock(eventId, 480);

        Optional<Stock> found = loadStockPort.loadStock(new EventId(eventId));

        assertThat(found).isPresent();
        assertThat(found.get().available()).isEqualTo(480);
        assertThat(found.get().version()).isZero();
    }

    @Test
    @DisplayName("SaveOrderPort 回傳帶有資料庫產生識別碼的訂單")
    void saveOrderReturnsPersistedOrderWithId() {
        long eventId = givenEvent(500);
        Order newOrder = Order.newOrder(
                new EventId(eventId),
                new UserId(1L),
                new Quantity(2),
                new IdempotencyKey("key-001"),
                Instant.parse("2026-09-06T00:00:00Z"));

        Order saved = saveOrderPort.saveOrder(newOrder);

        assertThat(saved.isPersisted()).isTrue();
        assertThat(saved.id().value()).isPositive();
        assertThat(saved.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(saved.quantity().value()).isEqualTo(2);
        assertThat(saved.idempotencyKey().value()).isEqualTo("key-001");
    }

    @Test
    @DisplayName("十個執行緒以相同冪等鍵同時下單,只有一筆成功")
    void concurrentSaveWithSameIdempotencyKeyAllowsOnlyOne() throws Exception {
        long eventId = givenEvent(500);
        int threadCount = 10;

        // ready:確保全部執行緒都已就緒;start:讓它們同時起跑。
        // 少了這兩道閘門,執行緒會依序執行完畢,測試就算約束失效也會通過 ——
        // 那是併發測試最常見的假綠燈。
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        try (ExecutorService pool = Executors.newFixedThreadPool(threadCount)) {
            for (int i = 0; i < threadCount; i++) {
                final long userId = i + 1L;
                pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        saveOrderPort.saveOrder(Order.newOrder(
                                new EventId(eventId),
                                new UserId(userId),
                                new Quantity(1),
                                new IdempotencyKey("same-key"),
                                Instant.parse("2026-09-06T00:00:00Z")));
                        succeeded.incrementAndGet();
                    } catch (Exception e) {
                        rejected.incrementAndGet();
                    }
                    return null;
                });
            }
            ready.await();
            start.countDown();
        }

        assertThat(succeeded.get()).isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(threadCount - 1);

        Long persisted = jdbc.queryForObject(
                "SELECT count(*) FROM purchase_order WHERE idempotency_key = ?", Long.class, "same-key");
        assertThat(persisted).isEqualTo(1L);
    }
}
