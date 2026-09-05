package com.alantsai.ticketrush.adapter.in.web;

import com.alantsai.ticketrush.adapter.in.web.dto.ApiErrorResponse;
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

    /** 冪等鍵唯一約束的名稱。用它區分「重複請求」與其他資料完整性錯誤。 */
    private static final String IDEMPOTENCY_CONSTRAINT = "uq_purchase_order_idempotency_key";

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
     * 資料完整性違反。
     *
     * <p>以約束名稱區分冪等鍵重複與其他情況 —— 不加區分一律回 409 DUPLICATE_REQUEST 會誤導:
     * 外鍵違反、CHECK 違反都會走到這裡,而它們不是「重複請求」。
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiErrorResponse> handleDataIntegrity(DataIntegrityViolationException e) {
        String detail = e.getMostSpecificCause().getMessage();
        if (detail != null && detail.contains(IDEMPOTENCY_CONSTRAINT)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiErrorResponse.of(ErrorCode.DUPLICATE_REQUEST, "相同的請求已經處理過"));
        }
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
