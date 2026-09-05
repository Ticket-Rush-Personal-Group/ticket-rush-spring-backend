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
    INTERNAL_ERROR
}
