package com.alantsai.ticketrush.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.alantsai.ticketrush.application.port.out.CompareAndDeductStockPort;
import com.alantsai.ticketrush.application.port.out.LoadStockPort;
import com.alantsai.ticketrush.domain.model.Stock;
import com.alantsai.ticketrush.domain.valueobject.EventId;
import com.alantsai.ticketrush.domain.valueobject.Quantity;
import com.alantsai.ticketrush.testsupport.TestcontainersConfiguration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 條件式 UPDATE 的行為驗證:版本相符才寫入。
 *
 * <p><b>兩個條件都要驗,缺一不可</b>:舊版本的 CAS 回傳 0,且最新版本的 CAS 回傳 1。
 * 只驗前者的話,一個「永遠回傳 0」的實作也會通過 —— 那是完全不能用的實作,卻能拿到綠燈。
 *
 * <p><b>刻意不用執行緒協調。</b> 悲觀鎖必須用兩條真實執行緒,因為它要驗的是「阻塞」,
 * 而阻塞只有在真的同時發生時才觀察得到。CAS 相反 —— 它**不阻塞**,失敗的那次是在對手提交**之後**
 * 才評估 WHERE 的(PostgreSQL 的 UPDATE 會先卡在對手持有的列鎖上,等它提交再重新比對)。
 * 因此「持有舊版本的交易 B 在交易 A 提交後嘗試 CAS」這個順序,用兩個依序執行的交易就能精確重現,
 * 而且沒有執行緒交錯帶來的偶發性。**用執行緒不會測得更真,只會測得更不穩。**
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class OptimisticCasBehaviourTest {

    private static final int INITIAL_STOCK = 500;

    @Autowired
    private LoadStockPort loadStockPort;

    @Autowired
    private CompareAndDeductStockPort compareAndDeductStockPort;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    private EventId eventId;

    @BeforeEach
    void setUp() {
        eventId = givenEventWithStock(INITIAL_STOCK);
    }

    @AfterEach
    void cleanup() {
        jdbc.execute("TRUNCATE purchase_order, stock, event RESTART IDENTITY CASCADE");
    }

    @Test
    @DisplayName("舊版本的 CAS 回傳 0 且不寫入,最新版本的 CAS 回傳 1")
    void staleVersionFailsAndCurrentVersionSucceeds() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        // 交易 A 與交易 B 都在版本 0 讀到庫存——這是競爭的起點。
        Stock readByA = tx.execute(status -> loadStockPort.loadStock(eventId).orElseThrow());
        Stock readByB = tx.execute(status -> loadStockPort.loadStock(eventId).orElseThrow());
        assertThat(readByA.version()).isEqualTo(readByB.version());

        // B 先成交：版本相符，寫入並把版本推進一格。
        int affectedByB = tx.execute(status -> compareAndDeductStockPort.compareAndDeduct(readByB.deduct(one())));
        assertThat(affectedByB).as("版本相符時 CAS 應成功").isEqualTo(1);
        assertThat(availableInDb()).isEqualTo(INITIAL_STOCK - 1);
        assertThat(versionInDb()).isEqualTo(readByB.version() + 1);

        // A 拿著已經過期的版本嘗試寫入——這正是樂觀鎖要偵測的情況。
        int affectedByA = tx.execute(status -> compareAndDeductStockPort.compareAndDeduct(readByA.deduct(one())));
        assertThat(affectedByA).as("版本已被他人推進時 CAS 應失敗").isZero();
        assertThat(availableInDb())
                .as("CAS 失敗時不得寫入——A 算出的 499 若寫進去，B 的扣減就消失了(lost update)")
                .isEqualTo(INITIAL_STOCK - 1);
        assertThat(versionInDb()).as("CAS 失敗時版本不得推進").isEqualTo(readByB.version() + 1);

        // A 重讀後再試一次：這就是重試迴圈在做的事，且它必須成功——
        // 否則「重試」只是換個方式失敗。
        Stock rereadByA = tx.execute(status -> loadStockPort.loadStock(eventId).orElseThrow());
        int affectedByRetry = tx.execute(status -> compareAndDeductStockPort.compareAndDeduct(rereadByA.deduct(one())));
        assertThat(affectedByRetry).as("重讀最新版本後 CAS 應成功").isEqualTo(1);
        assertThat(availableInDb()).isEqualTo(INITIAL_STOCK - 2);
    }

    @Test
    @DisplayName("CAS 成功時版本恰好推進 1，不是被寫成呼叫端傳入的值")
    void versionIsAdvancedByPersistenceLayerNotByCaller() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Stock read = tx.execute(status -> loadStockPort.loadStock(eventId).orElseThrow());

        // Stock.deduct 刻意不變動版本——版本的遞增由持久化層負責。
        Stock deducted = read.deduct(one());
        assertThat(deducted.version()).as("領域層不得變動版本").isEqualTo(read.version());

        tx.execute(status -> compareAndDeductStockPort.compareAndDeduct(deducted));

        assertThat(versionInDb()).isEqualTo(read.version() + 1);
    }

    private Quantity one() {
        return new Quantity(1);
    }

    private int availableInDb() {
        return jdbc.queryForObject("SELECT available FROM stock WHERE event_id = ?", Integer.class, eventId.value());
    }

    private long versionInDb() {
        return jdbc.queryForObject("SELECT version FROM stock WHERE event_id = ?", Long.class, eventId.value());
    }

    private EventId givenEventWithStock(int available) {
        Long id = jdbc.queryForObject(
                """
                INSERT INTO event (name, sales_start_at, total_quantity)
                VALUES (?, ?, ?) RETURNING id
                """,
                Long.class,
                "樂觀鎖行為測試場次",
                java.sql.Timestamp.from(Instant.parse("2026-12-01T12:00:00Z")),
                available);
        jdbc.update("INSERT INTO stock (event_id, available) VALUES (?, ?)", id, available);
        return new EventId(id);
    }
}
