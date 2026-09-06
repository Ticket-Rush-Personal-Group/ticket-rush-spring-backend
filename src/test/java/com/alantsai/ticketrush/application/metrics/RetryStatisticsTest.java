package com.alantsai.ticketrush.application.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link RetryStatistics} 的單元測試:分佈正確、耗盡另計、平均不取代分佈。 */
class RetryStatisticsTest {

    private static final int MAX_ATTEMPTS = 10;

    private final RetryStatistics statistics = new RetryStatistics(MAX_ATTEMPTS);

    @Test
    @DisplayName("依嘗試次數分桶計數")
    void countsByAttemptBucket() {
        statistics.recordAttempts(1);
        statistics.recordAttempts(1);
        statistics.recordAttempts(3);

        long[] distribution = statistics.distribution();

        assertThat(distribution[1]).isEqualTo(2);
        assertThat(distribution[2]).isZero();
        assertThat(distribution[3]).isEqualTo(1);
        assertThat(statistics.totalRecorded()).isEqualTo(3);
    }

    @Test
    @DisplayName("最大嘗試次數取自實際觀察，沒有紀錄時為 0")
    void reportsMaxObservedAttempts() {
        assertThat(statistics.maxObservedAttempts()).isZero();

        statistics.recordAttempts(1);
        statistics.recordAttempts(7);
        statistics.recordAttempts(2);

        assertThat(statistics.maxObservedAttempts()).isEqualTo(7);
    }

    @Test
    @DisplayName("平均值會抹平尾端 —— 這正是不能只看平均的理由")
    void averageHidesTheTail() {
        // 系統 A：9 個一次成功、1 個重試 10 次（少數倒楣鬼）
        RetryStatistics skewed = new RetryStatistics(MAX_ATTEMPTS);
        for (int i = 0; i < 9; i++) {
            skewed.recordAttempts(1);
        }
        skewed.recordAttempts(10);

        // 系統 B：10 個都重試到第 2 次才成功（全面性的輕度競爭）
        RetryStatistics uniform = new RetryStatistics(MAX_ATTEMPTS);
        for (int i = 0; i < 10; i++) {
            uniform.recordAttempts(2);
        }

        // 平均幾乎一樣（1.9 vs 2.0），但這是兩個完全不同的系統。
        assertThat(skewed.averageAttempts()).isEqualTo(1.9);
        assertThat(uniform.averageAttempts()).isEqualTo(2.0);
        // 分佈立刻分辨得出來。
        assertThat(skewed.maxObservedAttempts()).isEqualTo(10);
        assertThat(uniform.maxObservedAttempts()).isEqualTo(2);
    }

    @Test
    @DisplayName("重試耗盡另計，且同時計入分佈")
    void exhaustionIsCountedSeparatelyAndInDistribution() {
        statistics.recordAttempts(MAX_ATTEMPTS);
        statistics.recordExhaustion();

        assertThat(statistics.exhaustedCount()).isEqualTo(1);
        // 耗盡的請求確實嘗試了 MAX_ATTEMPTS 次，把它排除在分佈外會低估尾端。
        assertThat(statistics.distribution()[MAX_ATTEMPTS]).isEqualTo(1);
        assertThat(statistics.totalRecorded()).isEqualTo(1);
    }

    @Test
    @DisplayName("超出上限的嘗試次數被夾住，不拋例外")
    void clampsOutOfRangeAttempts() {
        statistics.recordAttempts(MAX_ATTEMPTS + 5);
        statistics.recordAttempts(0);

        // 統計元件不該成為系統當掉的原因——迴圈已保證不越界，夾住只是防禦。
        assertThat(statistics.distribution()[MAX_ATTEMPTS]).isEqualTo(1);
        assertThat(statistics.distribution()[1]).isEqualTo(1);
    }

    @Test
    @DisplayName("reset 清空分佈與耗盡數")
    void resetClearsEverything() {
        statistics.recordAttempts(5);
        statistics.recordExhaustion();

        statistics.reset();

        assertThat(statistics.totalRecorded()).isZero();
        assertThat(statistics.exhaustedCount()).isZero();
        assertThat(statistics.maxObservedAttempts()).isZero();
    }

    @Test
    @DisplayName("併發記錄不遺失計數 —— 它在 1000 併發下被呼叫")
    void countsAreNotLostUnderConcurrency() throws Exception {
        int threads = 200;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    start.await();
                    statistics.recordAttempts(2);
                    done.countDown();
                    return null;
                });
            }
            start.countDown();
            done.await();
        }

        // 用 long 而非 int 累加、用 Atomic 而非普通欄位，就是為了這個斷言。
        assertThat(statistics.distribution()[2]).isEqualTo(threads);
    }

    @Test
    @DisplayName("上限必須大於 0")
    void rejectsNonPositiveMaxAttempts() {
        assertThatThrownBy(() -> new RetryStatistics(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("重試上限");
    }
}
