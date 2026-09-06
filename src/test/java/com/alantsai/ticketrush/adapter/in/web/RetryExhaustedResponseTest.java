package com.alantsai.ticketrush.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.alantsai.ticketrush.adapter.in.web.dto.ApiErrorResponse;
import com.alantsai.ticketrush.application.exception.RetryExhaustedException;
import com.alantsai.ticketrush.domain.exception.InsufficientStockException;
import com.alantsai.ticketrush.domain.valueobject.EventId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * `RETRY_EXHAUSTED` 的回應契約,對應 {@code platform-api-response-format}。
 *
 * <p>直接測 handler 而非透過 MockMvc:本塊尚未有任何策略會拋出這個例外,
 * 用 HTTP 路徑測就必須先做出樂觀鎖 —— 那會讓塊 1 無法獨立通過驗證鏈。
 *
 * <p>要驗的是**契約**:狀態碼、碼名、以及「訊息不得洩漏內部細節」。這三件事與策略無關。
 */
class RetryExhaustedResponseTest {

    private static final int ATTEMPTS = 100;
    private static final long EVENT_ID = 7L;

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("重試耗盡回 409,code 為 RETRY_EXHAUSTED")
    void mapsToConflictWithRetryExhaustedCode() {
        ResponseEntity<ApiErrorResponse> response =
                handler.handleRetryExhausted(new RetryExhaustedException(new EventId(EVENT_ID), ATTEMPTS));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(ErrorCode.RETRY_EXHAUSTED.name());
        assertThat(response.getBody().success()).isFalse();
    }

    @Test
    @DisplayName("重試耗盡與庫存不足是不同的 code —— 兩者的處置相反")
    void isDistinctFromInsufficientStock() {
        String retryExhausted = handler.handleRetryExhausted(
                        new RetryExhaustedException(new EventId(EVENT_ID), ATTEMPTS))
                .getBody()
                .code();
        String insufficientStock = handler.handleInsufficientStock(
                        new InsufficientStockException(new EventId(EVENT_ID), 0, 1))
                .getBody()
                .code();

        // 合併成同一個 code 會讓客戶端做出錯誤決定：重試耗盡該重送，庫存不足該放棄。
        assertThat(retryExhausted).isNotEqualTo(insufficientStock);
        assertThat(retryExhausted).isEqualTo(ErrorCode.RETRY_EXHAUSTED.name());
    }

    @Test
    @DisplayName("回應訊息不含嘗試次數、場次或任何內部細節")
    void messageDoesNotLeakInternals() {
        RetryExhaustedException exception = new RetryExhaustedException(new EventId(EVENT_ID), ATTEMPTS);

        String message = handler.handleRetryExhausted(exception).getBody().message();

        // 例外本身的訊息是給日誌看的，含次數與場次；handler 不得原樣轉出。
        assertThat(exception.getMessage()).contains(String.valueOf(ATTEMPTS));
        assertThat(message)
                .doesNotContain(String.valueOf(ATTEMPTS))
                .doesNotContain(String.valueOf(EVENT_ID))
                .doesNotContain("版本")
                .doesNotContain("version");
    }
}
