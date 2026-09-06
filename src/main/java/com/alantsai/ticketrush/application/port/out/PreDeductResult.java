package com.alantsai.ticketrush.application.port.out;

/**
 * 快取預扣的結果。
 *
 * <p><b>刻意以列舉表達而非回傳整數碼。</b> 回傳碼是 Redis 腳本與 adapter 之間的協定,
 * 讓它洩漏到 application service 的判斷式裡,會使「{@code -2} 是什麼意思」這個問題
 * 必須跨層才答得出來。轉換由 adapter 負責 —— 那是協定的邊界。
 *
 * <p>四種結果分開表達而非合併為「成功 / 失敗」,理由與 {@code ErrorCode} 相同:
 * 呼叫端對三種失敗的處置不同(放棄 / 換場次 / 稍後再來),合併會讓它無從決定。
 */
public enum PreDeductResult {

    /** 預扣成功,庫存與已購數皆已更新。 */
    SUCCESS,

    /** 累計購買張數會超過單人上限。 */
    LIMIT_EXCEEDED,

    /** 可用庫存不足。 */
    INSUFFICIENT_STOCK,

    /**
     * 該場次尚未載入快取庫存。
     *
     * <p><b>不等於「場次不存在」,也不等於「庫存為 0」。</b> 場次可能存在於資料庫卻尚未開賣 ——
     * 把它當成庫存無限會直接造成超賣,把它說成「找不到」或「賣完了」則會誤導使用者。
     */
    NOT_ON_SALE
}
