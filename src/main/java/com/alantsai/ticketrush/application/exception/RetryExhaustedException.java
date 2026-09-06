package com.alantsai.ticketrush.application.exception;

import com.alantsai.ticketrush.domain.valueobject.EventId;

/**
 * 樂觀鎖重試達到上限仍未成功。
 *
 * <p><b>這不是「沒票了」。</b> 庫存可能仍然充足 —— 本例外表示的是該請求在版本競爭中連續搶輸,
 * 次數達到設定的上限。兩者的處置相反,因此對應不同的錯誤碼(見 {@code ErrorCode.RETRY_EXHAUSTED})。
 *
 * <p>訊息包含嘗試次數與場次,供伺服器日誌與壓測解讀使用。
 * <b>但它不得出現在 HTTP 回應中</b> —— 重試次數是內部實作細節,對客戶端沒有意義,
 * 而且會洩漏系統的競爭狀態。轉換由 {@code GlobalExceptionHandler} 負責。
 *
 * <p>重試耗盡率是本層的**量測項目**,不是要被消除的錯誤 ——
 * 它正是「樂觀鎖用在高競爭場景」的代價本身。
 */
public class RetryExhaustedException extends RuntimeException {

    private final transient EventId eventId;
    private final int attempts;

    public RetryExhaustedException(EventId eventId, int attempts) {
        super("樂觀鎖重試耗盡:場次 %d,已嘗試 %d 次仍因版本衝突失敗".formatted(eventId.value(), attempts));
        this.eventId = eventId;
        this.attempts = attempts;
    }

    public EventId eventId() {
        return eventId;
    }

    public int attempts() {
        return attempts;
    }
}
