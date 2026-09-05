package com.alantsai.ticketrush.domain.model;

import com.alantsai.ticketrush.domain.valueobject.EventId;
import java.time.Instant;

/**
 * 場次。
 *
 * <p>不含庫存 —— 庫存是獨立的聚合({@link Stock}),因為它是全系統競爭最激烈的資料,
 * 與場次的靜態資訊放在一起會讓讀取活動名稱也必須排隊。
 */
public record Event(EventId id, String name, Instant salesStartAt, int totalQuantity) {
    public Event {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("場次名稱不得為空");
        }
        if (salesStartAt == null) {
            throw new IllegalArgumentException("開賣時間不得為空");
        }
        if (totalQuantity <= 0) {
            throw new IllegalArgumentException("總票數必須大於 0,實際為 " + totalQuantity);
        }
    }
}
