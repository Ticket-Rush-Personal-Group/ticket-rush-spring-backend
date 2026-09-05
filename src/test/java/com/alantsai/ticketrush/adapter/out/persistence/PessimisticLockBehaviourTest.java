package com.alantsai.ticketrush.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.alantsai.ticketrush.application.port.out.LoadStockForUpdatePort;
import com.alantsai.ticketrush.domain.valueobject.EventId;
import com.alantsai.ticketrush.testsupport.TestcontainersConfiguration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 悲觀鎖的**行為**測試 —— 驗證的是「第二個交易真的會等」,不是「查得到資料」。
 *
 * <p>只驗證查詢回傳正確資料的測試,在 {@code @Lock} 被移除後依然全綠 ——
 * 那種測試對鎖而言毫無意義。這裡以兩個真實交易競爭同一列來驗證等待行為本身。
 *
 * <p>驗證手法:第二個交易在第一個持鎖期間 **必須 timeout**;第一個提交後它 **必須立即完成**。
 * 兩個條件缺一不可 —— 只驗前者的話,一個永遠卡住的實作也會通過。
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PessimisticLockBehaviourTest {

    private static final long BLOCKED_TIMEOUT_MS = 500;

    @Autowired
    private LoadStockForUpdatePort loadStockForUpdatePort;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void cleanup() {
        jdbc.execute("TRUNCATE purchase_order, stock, event RESTART IDENTITY CASCADE");
    }

    private long givenStock(int available) {
        Long eventId = jdbc.queryForObject(
                """
                INSERT INTO event (name, sales_start_at, total_quantity)
                VALUES (?, ?, ?) RETURNING id
                """, Long.class, "鎖行為測試場次", java.sql.Timestamp.from(Instant.parse("2026-12-01T12:00:00Z")), available);
        jdbc.update("INSERT INTO stock (event_id, available) VALUES (?, ?)", eventId, available);
        return eventId;
    }

    @Test
    @DisplayName("第一個交易持鎖時第二個必須等待,第一個提交後第二個立即完成")
    void secondTransactionWaitsUntilFirstCommits() throws Exception {
        long eventId = givenStock(500);
        EventId id = new EventId(eventId);

        CountDownLatch firstHoldsLock = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        // 明確的 executor，不用 ForkJoinPool.commonPool()——
        // 共用池的執行緒數受機器核心數影響，兩個阻塞任務可能搶不到執行緒而假性失敗。
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            CompletableFuture<Void> first = CompletableFuture.runAsync(
                    () -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                        loadStockForUpdatePort.loadStockForUpdate(id);
                        firstHoldsLock.countDown();
                        awaitQuietly(releaseFirst);
                    }),
                    pool);

            assertThat(firstHoldsLock.await(5, TimeUnit.SECONDS))
                    .as("第一個交易應在 5 秒內取得鎖")
                    .isTrue();

            CompletableFuture<Void> second = CompletableFuture.runAsync(
                    () -> new TransactionTemplate(transactionManager)
                            .executeWithoutResult(status -> loadStockForUpdatePort.loadStockForUpdate(id)),
                    pool);

            // 條件一：第一個持鎖期間，第二個拿不到鎖
            assertThatThrownBy(() -> second.get(BLOCKED_TIMEOUT_MS, TimeUnit.MILLISECONDS))
                    .as("第二個交易應被阻塞 —— 若立即完成，代表鎖沒有生效")
                    .isInstanceOf(TimeoutException.class);

            releaseFirst.countDown();
            first.get(5, TimeUnit.SECONDS);

            // 條件二：鎖釋放後第二個立即完成。少了這條，一個永遠卡住的實作也會通過條件一。
            assertThatCode(() -> second.get(5, TimeUnit.SECONDS))
                    .as("第一個交易提交後,第二個應立即取得鎖並完成")
                    .doesNotThrowAnyException();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待被中斷", e);
        }
    }
}
