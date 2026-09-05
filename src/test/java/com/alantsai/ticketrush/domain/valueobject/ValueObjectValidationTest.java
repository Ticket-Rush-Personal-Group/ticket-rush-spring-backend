package com.alantsai.ticketrush.domain.valueobject;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 值物件的建構驗證。
 *
 * <p>驗證發生在建構時而非使用時 —— 一個存在的 {@code Quantity} 實例必然是合法的張數,
 * 呼叫端不需要再檢查。這讓非法狀態在型別層級就無法表示。
 */
class ValueObjectValidationTest {

    @ParameterizedTest
    @ValueSource(ints = {0, -1, -100})
    @DisplayName("張數為零或負數時拒絕建構")
    void quantityRejectsNonPositive(int invalid) {
        assertThatThrownBy(() -> new Quantity(invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("張數必須大於 0");
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    @DisplayName("識別碼為零或負數時拒絕建構")
    void identifiersRejectNonPositive(long invalid) {
        assertThatThrownBy(() -> new EventId(invalid)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UserId(invalid)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderId(invalid)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("冪等鍵為空白時拒絕建構")
    void idempotencyKeyRejectsBlank() {
        assertThatThrownBy(() -> new IdempotencyKey("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不得為空");
    }

    @Test
    @DisplayName("冪等鍵超過 64 字元時拒絕建構 —— 對應資料庫的 VARCHAR(64)")
    void idempotencyKeyRejectsTooLong() {
        String tooLong = "x".repeat(65);

        assertThatThrownBy(() -> new IdempotencyKey(tooLong))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("長度不得超過 64");
    }

    @Test
    @DisplayName("冪等鍵剛好 64 字元是允許的")
    void idempotencyKeyAcceptsExactly64() {
        String exact = "x".repeat(64);

        assertThat(new IdempotencyKey(exact).value()).hasSize(64);
    }
}
