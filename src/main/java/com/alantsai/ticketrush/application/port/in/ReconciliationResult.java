package com.alantsai.ticketrush.application.port.in;

import com.alantsai.ticketrush.application.port.out.StreamBacklog;
import com.alantsai.ticketrush.domain.valueobject.EventId;

/**
 * 一次對帳的結果。
 *
 * <p>差額 = 快取已扣的張數 − 資料庫已記的張數。**它不是超賣** ——
 * 超賣的定義始終是「資料庫的訂單張數超過初始配額」,而差額代表的是
 * 「扣了但還沒記」或「扣了卻永遠不會記」。前者會自己消失,後者要靠回補。
 *
 * @param eventId 場次
 * @param preDeducted 快取已扣的張數(初始配額 − 快取餘量)
 * @param sold 資料庫已記錄的售出張數
 * @param discrepancy 差額
 * @param backlog 對帳當下的積壓量(已投遞未 ack + 是否有未投遞)
 * @param restored 是否執行了回補。**差額大於 0 但積壓非空時為 {@code false}** ——
 *     那是刻意的保守,見 {@code ReconcileStockService}
 */
public record ReconciliationResult(
        EventId eventId, int preDeducted, int sold, int discrepancy, StreamBacklog backlog, boolean restored) {

    /** 是否已收斂 —— 沒有差額,而且沒有訊息還在飛。 */
    public boolean converged() {
        return discrepancy == 0 && backlog.isEmpty();
    }
}
