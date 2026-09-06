package com.alantsai.ticketrush.adapter.in.web;

/**
 * 業務錯誤碼。
 *
 * <p><b>碼名描述發生了什麼事,不是 HTTP 狀態的複述。</b> {@code INSUFFICIENT_STOCK} 說明了原因,
 * {@code CONFLICT} 只是把客戶端已從狀態碼知道的事重複一次。客戶端要能單憑 code 決定如何處理,
 * 不需要解析 message —— 訊息是給人看的,可能被翻譯或改寫。
 *
 * <p>新增錯誤情境時,碼名 MUST 先於實作在 spec 中定名。
 */
public enum ErrorCode {
    INVALID_REQUEST,
    MISSING_USER_ID,
    EVENT_NOT_FOUND,
    PURCHASE_LIMIT_EXCEEDED,
    INSUFFICIENT_STOCK,
    DUPLICATE_REQUEST,
    /**
     * 樂觀鎖重試耗盡:**有票,但在版本競爭中連續搶輸到達上限。**
     *
     * <p><b>刻意不併入 {@link #INSUFFICIENT_STOCK}。</b> 兩者的意義相反 ——
     * 庫存不足是「沒票了」,重試耗盡是「還有票,只是你沒搶到」。客戶端對後者的合理反應是**重送**,
     * 對前者則是放棄或換場次。合併會讓客戶端做出錯誤的決定,而那正是錯誤碼要防止的事。
     */
    RETRY_EXHAUSTED,
    /**
     * 場次存在,但尚未載入快取庫存 —— **尚未開賣**。
     *
     * <p><b>刻意不併入 {@link #EVENT_NOT_FOUND} 或 {@link #INSUFFICIENT_STOCK}。</b>
     * 說成「找不到」會讓使用者以為連結壞了;說成「賣完了」會讓他放棄一場根本還沒開始賣的活動。
     * 兩種誤導都會讓使用者做出錯誤的決定,而那正是錯誤碼要防止的事。
     */
    EVENT_NOT_ON_SALE,
    INTERNAL_ERROR
}
