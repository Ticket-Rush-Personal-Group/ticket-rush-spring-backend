package com.alantsai.ticketrush.domain.valueobject;

/** 訂單識別碼。由資料庫產生,因此尚未持久化的訂單不會有它。 */
public record OrderId(long value) {
    public OrderId {
        if (value <= 0) {
            throw new IllegalArgumentException("OrderId 必須為正數,實際為 " + value);
        }
    }
}
