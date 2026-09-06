package com.alantsai.ticketrush.adapter.out.redis;

import com.alantsai.ticketrush.domain.valueobject.EventId;
import com.alantsai.ticketrush.domain.valueobject.UserId;

/**
 * Redis 的 key 命名。**集中一處,因為它有四個使用者。**
 *
 * <p>預扣 adapter、對帳 adapter、整合測試、壓測腳本都要拼出相同的 key。
 * 分散拼接必然漂移,而漂移的症狀是「對帳永遠算出差額」—— 它看起來像邏輯錯誤,
 * 實際上只是兩邊在讀不同的 key。
 *
 * <p><b>壓測腳本是唯一無法共用本類別的使用者</b>(它是 shell)。因此本類別的格式若變動,
 * {@code k6/run-load-test.sh} 必須一併修改 —— 那個耦合寫在這裡,不寫在腳本裡,
 * 是因為看得到程式碼的人才有機會發現它。
 *
 * <p>未加 hash tag:單節點假設。Redis Cluster 下同一支 Lua 腳本的 key 必須落在同一個 slot,
 * 屆時 {@code stock:7} 與 {@code purchased:7:42} 會分屬不同 slot 而無法執行。
 * Cluster 是 Phase 4 的議題。
 */
public final class RedisKeys {

    private RedisKeys() {}

    /** 某場次的可用庫存。值為整數字串,由 Lua 腳本以 {@code DECRBY} 扣減。 */
    public static String stock(EventId eventId) {
        return "stock:" + eventId.value();
    }

    /** 某使用者在某場次的累計購買張數。限購檢查的依據。 */
    public static String purchased(EventId eventId, UserId userId) {
        return "purchased:" + eventId.value() + ":" + userId.value();
    }
}
