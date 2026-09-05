package com.alantsai.ticketrush.domain.valueobject;

/**
 * 冪等鍵,由客戶端產生。
 *
 * <p>長度上限對應 {@code purchase_order.idempotency_key} 的 VARCHAR(64) —— 在領域層就擋下超長值,
 * 而不是等到資料庫拋出截斷錯誤。
 */
public record IdempotencyKey(String value) {
    private static final int MAX_LENGTH = 64;

    public IdempotencyKey {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("冪等鍵不得為空");
        }
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("冪等鍵長度不得超過 " + MAX_LENGTH + ",實際為 " + value.length());
        }
    }
}
