package com.alantsai.ticketrush.domain.model;

import com.alantsai.ticketrush.domain.exception.InsufficientStockException;
import com.alantsai.ticketrush.domain.valueobject.EventId;
import com.alantsai.ticketrush.domain.valueobject.Quantity;

/**
 * 庫存。四層併發策略競爭的對象。
 *
 * <p>刻意設計為不可變:{@link #deduct} 回傳新的實例而非修改自身。可變狀態在併發情境下是額外的
 * 心智負擔,而併發正是本專案的主題 —— 不可變讓領域物件天生 thread-safe,
 * 把併發問題完整侷限在持久化邊界,也就是我們真正想觀察的地方。
 *
 * <p>{@code version} 供樂觀鎖使用(第 7 支),扣減時不變動 —— 版本號的遞增由持久化層負責。
 */
public record Stock(EventId eventId, int available, long version) {
    public Stock {
        if (available < 0) {
            throw new IllegalArgumentException("庫存不得為負,實際為 " + available);
        }
        if (version < 0) {
            throw new IllegalArgumentException("版本號不得為負,實際為 " + version);
        }
    }

    /**
     * 扣減庫存,回傳扣減後的新實例。
     *
     * @param quantity 要扣減的張數
     * @return 扣減後的庫存
     * @throws InsufficientStockException 可用量不足時
     */
    public Stock deduct(Quantity quantity) {
        if (available < quantity.value()) {
            throw new InsufficientStockException(eventId, available, quantity.value());
        }
        return new Stock(eventId, available - quantity.value(), version);
    }
}
