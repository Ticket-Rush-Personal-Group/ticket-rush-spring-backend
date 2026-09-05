package com.alantsai.ticketrush.domain.exception;

import com.alantsai.ticketrush.domain.valueobject.EventId;

/**
 * 庫存不足。
 *
 * <p>這是領域規則的違反,不是基礎設施錯誤。資料庫的 {@code CHECK (available >= 0)} 是第二道防線,
 * 規則本身定義在 {@link com.alantsai.ticketrush.domain.model.Stock#deduct} ——
 * 領域規則必須能在沒有資料庫的情況下以純單元測試驗證。
 */
public class InsufficientStockException extends RuntimeException {

    private final transient EventId eventId;
    private final int available;
    private final int requested;

    public InsufficientStockException(EventId eventId, int available, int requested) {
        super("庫存不足:場次 %d 可用 %d 張,請求 %d 張".formatted(eventId.value(), available, requested));
        this.eventId = eventId;
        this.available = available;
        this.requested = requested;
    }

    public EventId eventId() {
        return eventId;
    }

    public int available() {
        return available;
    }

    public int requested() {
        return requested;
    }
}
