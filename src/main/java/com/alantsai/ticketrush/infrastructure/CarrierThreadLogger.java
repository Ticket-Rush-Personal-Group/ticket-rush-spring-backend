package com.alantsai.ticketrush.infrastructure;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.Arrays;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定期輸出目前存活的 carrier thread 數量,作為虛擬執行緒「執行並行度上限」的**實測值**。
 *
 * <p><b>為什麼不讀 {@code jdk.virtualThreadScheduler.parallelism} 這個系統屬性:</b>
 * 那是設定值,不是生效值。第 15 支已經踩過同一類的坑 ——
 * {@code server.tomcat.threads.max} 在虛擬執行緒下仍可被賦值卻不再約束請求處理,
 * 讀設定值會在條件表上寫下一個不是事實的數字,而
 * <b>條件表上錯誤的數字比缺漏的數字更危險:缺漏看得出來,錯誤看不出來。</b>
 *
 * <p>JDK 21 沒有公開 API 可以查詢虛擬執行緒排程器的並行度。但 carrier thread 是真實的平台執行緒,
 * 會出現在 {@link ThreadMXBean} 的執行緒列表裡 —— <b>數它們就是在數實際有幾條在跑。</b>
 *
 * <p><b>取最大值而非瞬時值。</b> carrier 是惰性建立的:啟動時只有少數幾條,
 * 要有足夠的併發負載才會長到並行度上限。單一時點的數字會低估,
 * 因此腳本取量測窗口內的最大值。
 *
 * <p>平台執行緒組態下這個數字為 0 —— <b>那是據實呈現「不適用」,不是量測失敗。</b>
 * 沒有虛擬執行緒就沒有 carrier。
 *
 * <p>與 {@link ConnectionPoolMetricsLogger} 共用同一個取樣間隔設定,
 * 且同樣**預設不存在** —— 該設定只在壓測用的 compose profile 裡出現。
 */
@Component
@ConditionalOnProperty(name = "ticket-rush.pool-metrics.interval-ms")
public class CarrierThreadLogger {

    private static final Logger log = LoggerFactory.getLogger(CarrierThreadLogger.class);

    /**
     * carrier thread 的名稱前綴。
     *
     * <p>JDK 21 的虛擬執行緒排程器是一個 {@code ForkJoinPool},其 worker 即為 carrier。
     * <b>這個前綴是實作細節,不是規格</b> —— 因此本元件的驗收是
     * 「調整並行度時這個數字要跟著變」,而不是「前綴永遠正確」。前綴若失效,數字會是 0,
     * 而 0 在虛擬執行緒組態下是不可能的,壓測腳本會據此中止。
     */
    private static final String CARRIER_PREFIX = "ForkJoinPool";

    private final ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();

    /** 輸出目前存活的 carrier 數量。標記固定,供壓測腳本取窗口內的最大值。 */
    @Scheduled(fixedDelayString = "${ticket-rush.pool-metrics.interval-ms}")
    public void logCarrierThreads() {
        long carriers = Arrays.stream(threadMXBean.dumpAllThreads(false, false))
                .map(java.lang.management.ThreadInfo::getThreadName)
                .filter(Objects::nonNull)
                .filter(name -> name.startsWith(CARRIER_PREFIX))
                .count();
        log.info("carrier 實測 : carriers={}", carriers);
    }
}
