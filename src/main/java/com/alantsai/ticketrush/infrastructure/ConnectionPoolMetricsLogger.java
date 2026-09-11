package com.alantsai.ticketrush.infrastructure;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定期輸出連線池的累計等待時間與持有時間,供壓測腳本取窗口差值。
 *
 * <p><b>為什麼要分「等待」與「持有」兩個數字:</b> 「等連線等很久」代表**資源不夠或競爭者太多**;
 * 「拿到之後佔很久」代表**每次使用做的事比較多或比較慢**。兩者對瓶頸的指認完全不同,
 * 處置也不同 —— 前者要加資源或減競爭者,後者要縮短使用。合計值把兩者混在一起,
 * 而混在一起就分不出該做哪一件。
 *
 * <p><b>為什麼是定期輸出,而不是像 {@link RetryStatisticsLogger} 那樣在關閉時輸出:</b>
 * 那個類別的判斷在它的情境下是對的(壓測期間的 I/O 會影響被量測的數字),但這裡不適用 ——
 * 關閉時拿到的是**自啟動以來的累計值**,而那包含暖機。暖機最多 10 輪、正式量測只有 1 輪,
 * **暖機會主宰整個數字**。要取得量測窗口內的差值,窗口兩端各需要一個取樣點。
 *
 * <p>也不用「打一個特殊端點來觸發輸出」—— 專案既有原則是**不為壓測在正式 API 開後門**。
 *
 * <p><b>而「定期輸出的成本可忽略」是一個機制宣稱,因此它被量過</b>,不是被假設 ——
 * 見本支 change 的塊 4.5。
 *
 * <p><b>輸出 count 與 totalTime 兩者,不輸出平均值。</b> 平均值無法在事後重新歸一化到
 * 「每請求」,而每請求才是可以跟 CPU 毫秒/請求並列比較的單位。
 *
 * <p>本元件**預設不存在** —— 只有設定了 {@code ticket-rush.pool-metrics.interval-ms}
 * 才會建立,而該設定只在壓測用的 compose profile 裡出現。一般執行與測試完全不受影響。
 */
@Component
@ConditionalOnProperty(name = "ticket-rush.pool-metrics.interval-ms")
public class ConnectionPoolMetricsLogger {

    private static final Logger log = LoggerFactory.getLogger(ConnectionPoolMetricsLogger.class);

    /** 取得連線所花的等待時間。Spring Boot 於 Micrometer 與 HikariCP 同時存在時自動註冊。 */
    private static final String ACQUIRE_TIMER = "hikaricp.connections.acquire";

    /** 連線自取得到歸還的持有時間。 */
    private static final String USAGE_TIMER = "hikaricp.connections.usage";

    /** 兩個 meter 都還沒註冊時輸出的值。**刻意不是 0** —— 見 {@link #logPoolMetrics()}。 */
    private static final String NOT_AVAILABLE = "NA";

    private final MeterRegistry meterRegistry;

    public ConnectionPoolMetricsLogger(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * 輸出兩個 Timer 的累計 count 與 totalTime。
     *
     * <p><b>每次都重新查詢 meter,不在建構時解析。</b> HikariCP 的 Micrometer 綁定要到
     * **第一次取得連線**才建立這兩個 meter —— 建構時解析會在應用啟動階段就失敗,
     * 而那時候「找不到」是正常的。
     *
     * <p><b>但找不到時輸出 {@code NA} 而不是 0。</b> 這是本元件最重要的一行:
     * **「沒有等待」與「沒量到」在數字上完全相同**,輸出 0 會讓整支實驗得出錯的結論
     * 且沒有任何徵兆。輸出 NA 則會讓壓測腳本的解析直接失敗。
     *
     * <p>標記字串固定,供壓測腳本以 grep 取窗口兩端的值相減。**累計值相減不需要精確的括號** ——
     * 分母是已知的請求數而不是時間,而閒置期間不會產生連線取得,多包到的空白貢獻為零。
     */
    @Scheduled(fixedDelayString = "${ticket-rush.pool-metrics.interval-ms}")
    public void logPoolMetrics() {
        Timer acquire = meterRegistry.find(ACQUIRE_TIMER).timer();
        Timer usage = meterRegistry.find(USAGE_TIMER).timer();
        if (acquire == null || usage == null) {
            log.info(
                    "連線池累計 : acquire_count={} acquire_total_ms={} usage_count={} usage_total_ms={}",
                    NOT_AVAILABLE,
                    NOT_AVAILABLE,
                    NOT_AVAILABLE,
                    NOT_AVAILABLE);
            return;
        }
        log.info(
                "連線池累計 : acquire_count={} acquire_total_ms={} usage_count={} usage_total_ms={}",
                acquire.count(),
                String.format("%.3f", acquire.totalTime(TimeUnit.MILLISECONDS)),
                usage.count(),
                String.format("%.3f", usage.totalTime(TimeUnit.MILLISECONDS)));
    }
}
