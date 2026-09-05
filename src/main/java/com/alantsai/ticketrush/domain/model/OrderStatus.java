package com.alantsai.ticketrush.domain.model;

/**
 * 訂單狀態。
 *
 * <p>Phase 1 只會出現 PENDING —— 付款與逾時取消屬於 Phase 2 的訂單狀態機。
 * 狀態值先行定義,避免屆時變更 {@code purchase_order.status} 的既有資料。
 */
public enum OrderStatus {
    PENDING,
    PAID,
    CANCELLED,
    EXPIRED
}
