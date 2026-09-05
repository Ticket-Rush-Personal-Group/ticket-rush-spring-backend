package com.alantsai.ticketrush.application.port.out;

import com.alantsai.ticketrush.domain.valueobject.EventId;
import com.alantsai.ticketrush.domain.valueobject.UserId;

/**
 * 查詢某使用者在某場次的**累計購買張數**。
 *
 * <p>回傳的是張數總和而非訂單筆數 —— 一次買 4 張與四次各買 1 張,對限購而言等價。
 *
 * <p>目前不排除任何訂單狀態。Phase 2 引入逾時取消後,已取消的訂單應被排除;
 * 現在只有 PENDING 狀態,該邏輯無法驗證,故記入 {@code tasks/todo.md} 而不預先實作。
 */
public interface LoadUserPurchasedQuantityPort {

    int loadPurchasedQuantity(EventId eventId, UserId userId);
}
