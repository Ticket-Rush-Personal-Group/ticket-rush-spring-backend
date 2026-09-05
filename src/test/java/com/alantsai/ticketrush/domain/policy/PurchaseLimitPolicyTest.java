package com.alantsai.ticketrush.domain.policy;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.alantsai.ticketrush.domain.exception.PurchaseLimitExceededException;
import com.alantsai.ticketrush.domain.valueobject.Quantity;
import com.alantsai.ticketrush.domain.valueobject.UserId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 限購規則的單元測試。**不啟動 Spring、不連資料庫。**
 *
 * <p>這是「領域規則不依賴基礎設施」的實際好處:最核心的業務邏輯可以在最快的測試迴圈裡開發,
 * 而邊界條件的窮舉在整合測試裡成本高到沒人會做。
 *
 * <p>限購最典型的 bug 是**差一錯誤**(`>` 寫成 `>=`),因此「剛好等於上限」這個案例
 * 必須獨立存在,不能只測「明顯超過」與「明顯未達」。
 */
class PurchaseLimitPolicyTest {

    private static final UserId USER = new UserId(1L);
    private static final PurchaseLimitPolicy POLICY = new PurchaseLimitPolicy(4);

    @ParameterizedTest(name = "已購 {0} 張 + 本次 {1} 張 = {2} 張,未超過上限 4")
    @CsvSource({"0, 1, 1", "0, 4, 4", "2, 2, 4", "3, 1, 4"})
    @DisplayName("累計未超過上限時通過")
    void allowsWhenWithinLimit(int alreadyPurchased, int requesting, int total) {
        assertThatCode(() -> POLICY.ensureWithinLimit(USER, alreadyPurchased, new Quantity(requesting)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("累計剛好等於上限時通過 —— 邊界值,差一錯誤最容易發生在這裡")
    void allowsWhenExactlyAtLimit() {
        assertThatCode(() -> POLICY.ensureWithinLimit(USER, 3, new Quantity(1))).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "已購 {0} 張 + 本次 {1} 張,超過上限 4")
    @CsvSource({"3, 2", "4, 1", "0, 5", "2, 3"})
    @DisplayName("累計超過上限時拋出例外")
    void rejectsWhenExceedingLimit(int alreadyPurchased, int requesting) {
        assertThatThrownBy(() -> POLICY.ensureWithinLimit(USER, alreadyPurchased, new Quantity(requesting)))
                .isInstanceOf(PurchaseLimitExceededException.class);
    }

    @Test
    @DisplayName("單次請求即超過上限")
    void rejectsWhenSingleRequestExceedsLimit() {
        assertThatThrownBy(() -> POLICY.ensureWithinLimit(USER, 0, new Quantity(5)))
                .isInstanceOf(PurchaseLimitExceededException.class);
    }

    @Test
    @DisplayName("例外訊息包含超出的張數,而不只是「超過了」")
    void exceptionMessageStatesHowMuchOver() {
        assertThatThrownBy(() -> POLICY.ensureWithinLimit(USER, 3, new Quantity(3)))
                .hasMessageContaining("已購 3 張")
                .hasMessageContaining("本次請求 3 張")
                .hasMessageContaining("上限 4 張")
                .hasMessageContaining("超出 2 張");
    }

    @Test
    @DisplayName("上限為零或負數時拒絕建構")
    void rejectsNonPositiveLimit() {
        assertThatThrownBy(() -> new PurchaseLimitPolicy(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PurchaseLimitPolicy(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("已購張數為負時拒絕 —— 那代表查詢邏輯出錯,不該被當成合法輸入")
    void rejectsNegativeAlreadyPurchased() {
        assertThatThrownBy(() -> POLICY.ensureWithinLimit(USER, -1, new Quantity(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
