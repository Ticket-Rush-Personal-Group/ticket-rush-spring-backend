package com.alantsai.ticketrush.domain.valueobject;

/**
 * 場次識別碼。
 *
 * <p>以型別取代裸露的 long,避免與 UserId、OrderId 在參數列中互相錯置 —— 那類錯誤編譯期不會發現,
 * 執行期也不會拋例外,只是資料連到錯的地方。
 */
public record EventId(long value) {
    public EventId {
        if (value <= 0) {
            throw new IllegalArgumentException("EventId 必須為正數,實際為 " + value);
        }
    }
}
