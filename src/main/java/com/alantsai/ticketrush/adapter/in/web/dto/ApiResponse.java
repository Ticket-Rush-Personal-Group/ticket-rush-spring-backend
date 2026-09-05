package com.alantsai.ticketrush.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/**
 * 成功回應的統一 wrapper。
 *
 * <p>{@code @JsonInclude(NON_NULL)} 使 {@code data} 為 null 時**整個 key 不存在**,
 * 而不是輸出 {@code "data": null}。兩者對客戶端是不同的訊號:缺少 key 表示「本操作沒有回傳內容」,
 * null 表示「有這個欄位但值為空」。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(boolean success, T data, Instant timestamp) {

    public static <T> ApiResponse<T> of(T data) {
        return new ApiResponse<>(true, data, Instant.now());
    }

    /** 無回傳內容的成功回應。序列化後不含 {@code data} key。 */
    public static ApiResponse<Void> empty() {
        return new ApiResponse<>(true, null, Instant.now());
    }
}
