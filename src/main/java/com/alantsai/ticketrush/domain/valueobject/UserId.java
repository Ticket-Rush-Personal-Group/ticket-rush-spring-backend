package com.alantsai.ticketrush.domain.valueobject;

/** 使用者識別碼。Phase 1 由 request header 帶入,不做認證。 */
public record UserId(long value) {
    public UserId {
        if (value <= 0) {
            throw new IllegalArgumentException("UserId 必須為正數,實際為 " + value);
        }
    }
}
