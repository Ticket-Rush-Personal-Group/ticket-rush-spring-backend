package com.alantsai.ticketrush.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.alantsai.ticketrush.domain.exception.InsufficientStockException;
import com.alantsai.ticketrush.domain.valueobject.EventId;
import com.alantsai.ticketrush.domain.valueobject.Quantity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 庫存的領域規則測試。
 *
 * <p><b>刻意不啟動 Spring context、不連資料庫。</b>「扣減不得超過可用量」是領域規則,
 * 必須能獨立於基礎設施驗證 —— 資料庫的 {@code CHECK (available >= 0)} 是第二道防線,
 * 不是規則的定義處。若這條規則只有整合測試涵蓋,就等於把領域邏輯的正確性押在資料庫約束上。
 */
class StockTest {

    private static final EventId EVENT_ID = new EventId(1L);

    @Test
    @DisplayName("扣減後可用量減少對應張數")
    void deductReducesAvailable() {
        Stock stock = new Stock(EVENT_ID, 500, 0);

        Stock result = stock.deduct(new Quantity(3));

        assertThat(result.available()).isEqualTo(497);
    }

    @Test
    @DisplayName("扣減至剛好為零是允許的")
    void deductToExactlyZeroIsAllowed() {
        Stock stock = new Stock(EVENT_ID, 2, 0);

        Stock result = stock.deduct(new Quantity(2));

        assertThat(result.available()).isZero();
    }

    @Test
    @DisplayName("扣減超過可用量拋出庫存不足")
    void deductBeyondAvailableThrows() {
        Stock stock = new Stock(EVENT_ID, 3, 0);

        assertThatThrownBy(() -> stock.deduct(new Quantity(5)))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("可用 3 張")
                .hasMessageContaining("請求 5 張");
    }

    @Test
    @DisplayName("扣減不變動版本號 —— 版本遞增由持久化層負責")
    void deductDoesNotChangeVersion() {
        Stock stock = new Stock(EVENT_ID, 10, 7);

        Stock result = stock.deduct(new Quantity(1));

        assertThat(result.version()).isEqualTo(7);
    }

    @Test
    @DisplayName("扣減回傳新實例,原物件不變 —— 不可變性保證併發安全")
    void deductReturnsNewInstanceLeavingOriginalIntact() {
        Stock stock = new Stock(EVENT_ID, 10, 0);

        Stock result = stock.deduct(new Quantity(4));

        assertThat(stock.available()).isEqualTo(10);
        assertThat(result).isNotSameAs(stock);
    }

    @Test
    @DisplayName("建構時拒絕負庫存")
    void rejectsNegativeAvailableOnConstruction() {
        assertThatThrownBy(() -> new Stock(EVENT_ID, -1, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("庫存不得為負");
    }
}
