/**
 * 策略機制本身的量測。
 *
 * <p><b>放在 application 而非 infrastructure,因為量的是策略的行為,不是執行環境。</b>
 * 重試次數是樂觀鎖流程的一部分,由持有重試迴圈的 application service 產生 ——
 * 若放進 infrastructure,application 就得反向依賴它。
 *
 * <p>輸出端(何時把數字印出來)才屬於 infrastructure,見
 * {@code infrastructure.RetryStatisticsLogger}。**收集與呈現分開**:
 * 收集是策略的一部分,呈現是執行環境的一部分。
 *
 * <p>本套件不是 Phase 4 觀測方案的前身。Micrometer / Prometheus 於 Phase 4 引入時,
 * 這裡的計數器要不要換掉是屆時的決定,現在不預先遷就它。
 */
package com.alantsai.ticketrush.application.metrics;
