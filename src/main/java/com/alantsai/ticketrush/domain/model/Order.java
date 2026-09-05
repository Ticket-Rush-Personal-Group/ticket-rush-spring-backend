package com.alantsai.ticketrush.domain.model;

import com.alantsai.ticketrush.domain.valueobject.EventId;
import com.alantsai.ticketrush.domain.valueobject.IdempotencyKey;
import com.alantsai.ticketrush.domain.valueobject.OrderId;
import com.alantsai.ticketrush.domain.valueobject.Quantity;
import com.alantsai.ticketrush.domain.valueobject.UserId;
import java.time.Instant;

/**
 * 訂單。
 *
 * <p>{@code id} 在尚未持久化時為 {@code null} —— 它由資料庫產生。以 {@link #newOrder} 建立新訂單,
 * 持久化後才會有識別碼。這是唯一允許 null 的欄位,其餘皆於建構時驗證。
 */
public record Order(
        OrderId id,
        EventId eventId,
        UserId userId,
        Quantity quantity,
        OrderStatus status,
        IdempotencyKey idempotencyKey,
        Instant createdAt) {

    public Order {
        if (eventId == null || userId == null || quantity == null || status == null) {
            throw new IllegalArgumentException("訂單的場次、使用者、張數、狀態皆不得為空");
        }
        if (idempotencyKey == null) {
            throw new IllegalArgumentException("冪等鍵不得為空 —— 沒有它就無法防止重試造成重複訂單");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("建立時間不得為空");
        }
    }

    /** 建立一筆尚未持久化的訂單,狀態為 PENDING、無識別碼。 */
    public static Order newOrder(
            EventId eventId, UserId userId, Quantity quantity, IdempotencyKey idempotencyKey, Instant createdAt) {
        return new Order(null, eventId, userId, quantity, OrderStatus.PENDING, idempotencyKey, createdAt);
    }

    /** 是否已持久化(具有資料庫產生的識別碼)。 */
    public boolean isPersisted() {
        return id != null;
    }
}
