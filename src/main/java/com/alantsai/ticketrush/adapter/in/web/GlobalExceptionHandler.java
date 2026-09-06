package com.alantsai.ticketrush.adapter.in.web;

import com.alantsai.ticketrush.adapter.in.web.dto.ApiErrorResponse;
import com.alantsai.ticketrush.application.exception.DuplicateOrderException;
import com.alantsai.ticketrush.application.exception.EventNotOnSaleException;
import com.alantsai.ticketrush.application.exception.RetryExhaustedException;
import com.alantsai.ticketrush.domain.exception.EventNotFoundException;
import com.alantsai.ticketrush.domain.exception.InsufficientStockException;
import com.alantsai.ticketrush.domain.exception.PurchaseLimitExceededException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 領域例外與框架例外到 HTTP 回應的統一映射。
 *
 * <p>失敗回應只在此處產生。分散到各 controller 組裝必然漂移,而漂移的症狀是
 * 「某幾個端點的錯誤格式跟其他的不一樣」,客戶端要為此寫特例。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(EventNotFoundException.class)
    ResponseEntity<ApiErrorResponse> handleEventNotFound(EventNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiErrorResponse.of(ErrorCode.EVENT_NOT_FOUND, e.getMessage()));
    }

    @ExceptionHandler(PurchaseLimitExceededException.class)
    ResponseEntity<ApiErrorResponse> handlePurchaseLimitExceeded(PurchaseLimitExceededException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiErrorResponse.of(ErrorCode.PURCHASE_LIMIT_EXCEEDED, e.getMessage()));
    }

    @ExceptionHandler(InsufficientStockException.class)
    ResponseEntity<ApiErrorResponse> handleInsufficientStock(InsufficientStockException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiErrorResponse.of(ErrorCode.INSUFFICIENT_STOCK, e.getMessage()));
    }

    /**
     * 樂觀鎖重試耗盡。
     *
     * <p><b>訊息是固定字串,不使用 {@code e.getMessage()}。</b> 例外訊息含嘗試次數與場次 ——
     * 那是給日誌看的,對客戶端沒有意義,而且會洩漏系統當下的競爭狀態。
     *
     * <p>以 warn 而非 error 記錄:重試耗盡是本層**預期會發生**的失敗模式,不是系統故障。
     * 記成 error 會讓壓測期間的日誌被它淹沒,真正的錯誤反而看不見。
     */
    @ExceptionHandler(RetryExhaustedException.class)
    ResponseEntity<ApiErrorResponse> handleRetryExhausted(RetryExhaustedException e) {
        log.warn("樂觀鎖重試耗盡:場次 {},嘗試 {} 次", e.eventId().value(), e.attempts());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiErrorResponse.of(ErrorCode.RETRY_EXHAUSTED, "系統忙碌,請稍後重試"));
    }

    /**
     * 場次尚未開賣(第 3 層:快取中沒有該場次的庫存)。
     *
     * <p>回 409 而非 404:場次**存在**,只是還沒開賣。404 會讓使用者以為連結有誤。
     */
    @ExceptionHandler(EventNotOnSaleException.class)
    ResponseEntity<ApiErrorResponse> handleEventNotOnSale(EventNotOnSaleException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiErrorResponse.of(ErrorCode.EVENT_NOT_ON_SALE, "場次尚未開賣,請稍後再試"));
    }

    /**
     * 冪等鍵重複。
     *
     * <p><b>接的是 {@link DuplicateOrderException} 而非 {@code DataIntegrityViolationException}。</b>
     * 約束違反在**持久化 adapter** 就被翻譯成應用層的例外了 —— 那是 adapter 的職責,
     * 而且讓翻譯只發生在一個地方:落庫的消費者與本 handler 因此看到的是同一種例外,
     * 不必各自比對約束名稱。**各自比對的風險不是打錯字(那會立刻壞),而是只改了其中一處** ——
     * 那時一邊仍能辨識、另一邊不能。
     */
    @ExceptionHandler(DuplicateOrderException.class)
    ResponseEntity<ApiErrorResponse> handleDuplicateOrder(DuplicateOrderException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiErrorResponse.of(ErrorCode.DUPLICATE_REQUEST, "相同的請求已經處理過"));
    }

    /**
     * 其餘的資料完整性違反。
     *
     * <p>外鍵違反、CHECK 違反都會走到這裡,而它們不是「重複請求」——
     * 一律回 409 DUPLICATE_REQUEST 會誤導客戶端去做無意義的重送。
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiErrorResponse> handleDataIntegrity(DataIntegrityViolationException e) {
        log.error("未預期的資料完整性違反", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiErrorResponse.of(ErrorCode.INTERNAL_ERROR, "系統發生非預期的錯誤"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ":" + error.getDefaultMessage())
                .findFirst()
                .orElse("請求格式不正確");
        return ResponseEntity.badRequest().body(ApiErrorResponse.of(ErrorCode.INVALID_REQUEST, message));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ApiErrorResponse> handleMissingHeader(MissingRequestHeaderException e) {
        if ("X-User-Id".equalsIgnoreCase(e.getHeaderName())) {
            return ResponseEntity.badRequest().body(ApiErrorResponse.of(ErrorCode.MISSING_USER_ID, "缺少 X-User-Id 標頭"));
        }
        return ResponseEntity.badRequest()
                .body(ApiErrorResponse.of(ErrorCode.INVALID_REQUEST, "缺少必要的標頭:" + e.getHeaderName()));
    }

    /** value object 的建構驗證失敗(最後一道防線,正常情況下 Bean Validation 已先擋下)。 */
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiErrorResponse> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(ApiErrorResponse.of(ErrorCode.INVALID_REQUEST, e.getMessage()));
    }

    /**
     * 未預期的例外。
     *
     * <p><b>回應中不得出現 SQL、堆疊軌跡或內部類別名稱。</b> 詳細資訊只寫入伺服器日誌。
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiErrorResponse> handleUnexpected(Exception e) {
        log.error("未預期的錯誤", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiErrorResponse.of(ErrorCode.INTERNAL_ERROR, "系統發生非預期的錯誤"));
    }
}
