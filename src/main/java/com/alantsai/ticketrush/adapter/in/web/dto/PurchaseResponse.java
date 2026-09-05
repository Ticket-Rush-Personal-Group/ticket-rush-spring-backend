package com.alantsai.ticketrush.adapter.in.web.dto;

import com.alantsai.ticketrush.application.port.in.PurchaseResult;

/**
 * 購票回應。
 *
 * <p><b>不含當前策略的任何資訊。</b> 四層策略共用同一契約 —— 一旦洩漏,呼叫端就可能依它分支,
 * 策略便不再可自由抽換。
 */
public record PurchaseResponse(long orderId, long eventId, int quantity, String status) {

    public static PurchaseResponse from(PurchaseResult result) {
        return new PurchaseResponse(
                result.orderId().value(),
                result.eventId().value(),
                result.quantity().value(),
                result.status().name());
    }
}
