package com.alantsai.ticketrush.application.metrics;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 樂觀鎖的重試次數統計。**本層的頭號量測項目。**
 *
 * <p><b>記錄的是分佈,不是平均值。</b> 平均值會把「多數請求一次成功、少數重試五十次」與
 * 「所有請求都重試三次」畫成同一個數字,而那是兩個完全不同的系統 —— 前者只有少數倒楣鬼,
 * 後者是全面性的競爭。搶票要看的正是尾端,而平均值恰好把尾端抹掉。
 *
 * <p><b>為什麼是行程內的計數器而不是別的做法:</b>
 *
 * <ul>
 *   <li><b>不在回應加 {@code X-Retry-Count} 標頭</b> —— 汙染正式 API 契約。
 *       {@code k6/run-load-test.sh} 的既有註解已定調:不為壓測在應用開後門。
 *   <li><b>不每個請求記一行 log</b> —— 1000 併發下的 log I/O 會影響**被量測的數字本身**。
 *       觀測手段改變被觀測的對象,是壓測最常見的自我汙染。
 *   <li><b>不引入 Micrometer</b> —— 觀測方案是 Phase 4 的決定,為了一個計數器提前拉進整套
 *       registry 不成比例。
 * </ul>
 *
 * <p>計數以 {@link AtomicLongArray} 依「嘗試次數」分桶,索引即嘗試次數(索引 0 不使用)。
 * 陣列大小固定為上限 + 1 —— 嘗試次數由重試迴圈限制,不可能超出。
 */
@Component
public class RetryStatistics {

    private final int maxAttempts;
    private final AtomicLongArray countByAttempts;
    private final AtomicLong exhausted = new AtomicLong();

    public RetryStatistics(@Value("${ticket-rush.optimistic.max-attempts}") int maxAttempts) {
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("重試上限必須大於 0,實際為 " + maxAttempts);
        }
        this.maxAttempts = maxAttempts;
        this.countByAttempts = new AtomicLongArray(maxAttempts + 1);
    }

    /**
     * 記錄一個請求實際用掉的嘗試次數。
     *
     * <p>成功、庫存不足、重試耗盡都要記 —— **只記成功的會讓分佈失真**:
     * 售罄後被快速拒絕的請求嘗試次數為 1,把它們排除會讓平均與尾端同時被高估。
     *
     * @param attempts 該請求的嘗試次數,至少為 1
     */
    public void recordAttempts(int attempts) {
        // 由重試迴圈保證不越界；夾住只是不讓一個統計元件成為當掉的原因。
        int bucket = Math.min(Math.max(attempts, 1), maxAttempts);
        countByAttempts.incrementAndGet(bucket);
    }

    /** 另計重試耗盡。耗盡的請求同時也會被 {@link #recordAttempts} 計入分佈。 */
    public void recordExhaustion() {
        exhausted.incrementAndGet();
    }

    /**
     * 嘗試次數的分佈。索引即嘗試次數,索引 0 恆為 0。
     *
     * @return 一份快照;呼叫端修改它不影響統計
     */
    public long[] distribution() {
        long[] snapshot = new long[maxAttempts + 1];
        for (int i = 1; i <= maxAttempts; i++) {
            snapshot[i] = countByAttempts.get(i);
        }
        return snapshot;
    }

    /**
     * 觀察到的最大嘗試次數。
     *
     * <p><b>併發正確性測試會斷言它大於 1。</b> 全部一次成功代表測試根本沒有製造出競爭 ——
     * 那種綠燈毫無意義,而且它跟真的通過長得一模一樣。
     */
    public int maxObservedAttempts() {
        for (int i = maxAttempts; i >= 1; i--) {
            if (countByAttempts.get(i) > 0) {
                return i;
            }
        }
        return 0;
    }

    /** 已記錄的請求總數。 */
    public long totalRecorded() {
        long total = 0;
        for (int i = 1; i <= maxAttempts; i++) {
            total += countByAttempts.get(i);
        }
        return total;
    }

    /** 重試耗盡的請求數。**這是量測項目,不是要被消除的錯誤。** */
    public long exhaustedCount() {
        return exhausted.get();
    }

    /** 平均嘗試次數。**輔助用,不得取代分佈** —— 它會把尾端抹平。 */
    public double averageAttempts() {
        long total = 0;
        long weighted = 0;
        for (int i = 1; i <= maxAttempts; i++) {
            long count = countByAttempts.get(i);
            total += count;
            weighted += count * i;
        }
        return total == 0 ? 0 : (double) weighted / total;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    /**
     * 清空統計。
     *
     * <p>供測試使用 —— Spring 的 test context 快取會讓同一個實例跨測試方法存活,
     * 前一個測試的數字會汙染後一個的斷言。
     */
    public void reset() {
        for (int i = 0; i <= maxAttempts; i++) {
            countByAttempts.set(i, 0);
        }
        exhausted.set(0);
    }
}
