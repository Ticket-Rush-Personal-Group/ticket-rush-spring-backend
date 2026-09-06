package com.alantsai.ticketrush.adapter.in.web.dto;

import com.alantsai.ticketrush.application.port.in.PurchaseResult;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 購票回應。
 *
 * <p><b>不含當前策略的任何資訊。</b> 四層策略共用同一契約 —— 一旦洩漏策略身分,
 * 呼叫端就可能依它分支,策略便不再可自由抽換。
 *
 * <p><b>回應永遠帶著一個能指認這筆訂單的東西:訂單識別碼,或冪等鍵。</b>
 *
 * <ul>
 *   <li><b>已建立</b>(同步落庫的第 0 / 1 / 2 層)—— 回 {@code orderId}
 *   <li><b>已受理</b>(非同步落庫的第 3 層)—— 訂單尚未建立,無 {@code orderId} 可回,
 *       改回 {@code idempotencyKey} 作為後續查詢的依據
 * </ul>
 *
 * <p>{@code @JsonInclude(NON_NULL)} 讓兩者互不干擾:同步策略的回應**與本支之前完全相同**
 * (不會多出一個 {@code "idempotencyKey": null}),非同步策略則不會出現一個謊稱存在的
 * {@code "orderId": null}。**沒有值的欄位不出現,比出現一個 null 更接近事實** ——
 * 這與 {@code ApiResponse} 既有的處置一致。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PurchaseResponse(Long orderId, long eventId, int quantity, String status, String idempotencyKey) {

    /** 訂單已建立於資料庫,以識別碼回應。 */
    public static PurchaseResponse created(PurchaseResult result) {
        return new PurchaseResponse(
                result.orderId().value(),
                result.eventId().value(),
                result.quantity().value(),
                result.status().name(),
                null);
    }

    /**
     * 訂單已受理但尚未建立,以冪等鍵回應。
     *
     * <p>冪等鍵來自請求本身 —— 它是客戶端自己產生的,回傳它不洩漏任何內部狀態,
     * 而且正好是客戶端稍後用來查詢這筆訂單的依據。
     */
    public static PurchaseResponse accepted(PurchaseResult result, String idempotencyKey) {
        return new PurchaseResponse(
                null,
                result.eventId().value(),
                result.quantity().value(),
                result.status().name(),
                idempotencyKey);
    }
}
