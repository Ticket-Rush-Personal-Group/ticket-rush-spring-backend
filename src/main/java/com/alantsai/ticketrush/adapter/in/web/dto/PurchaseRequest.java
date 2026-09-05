package com.alantsai.ticketrush.adapter.in.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 購票請求。
 *
 * <p>驗證在此發生,**不合法的請求不會進入 application service** —— value object 的建構驗證
 * 是最後一道防線,但它拋出的是 {@code IllegalArgumentException},對客戶端而言訊息不夠明確。
 * Bean Validation 能指出是哪個欄位、為什麼不合法。
 */
public record PurchaseRequest(
        @Positive(message = "張數必須大於 0") int quantity,

        @NotBlank(message = "冪等鍵不得為空") @Size(max = 64, message = "冪等鍵長度不得超過 64")
        String idempotencyKey) {}
