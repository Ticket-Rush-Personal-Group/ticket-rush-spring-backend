package com.alantsai.ticketrush.adapter.in.web.dto;

import com.alantsai.ticketrush.adapter.in.web.ErrorCode;
import java.time.Instant;

/**
 * 失敗回應的統一 wrapper。**不含 {@code data}**。
 *
 * <p>只由 {@code GlobalExceptionHandler} 產生,不得在個別 controller 內組裝 ——
 * 分散組裝必然漂移,而漂移的症狀是「某幾個端點的錯誤格式跟其他的不一樣」,
 * 客戶端要為此寫特例。
 */
public record ApiErrorResponse(boolean success, String message, String code, Instant timestamp) {

    public static ApiErrorResponse of(ErrorCode code, String message) {
        return new ApiErrorResponse(false, message, code.name(), Instant.now());
    }
}
