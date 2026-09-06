package com.alantsai.ticketrush.application.port.out;

/**
 * 訊息串流上「尚未變成訂單」的積壓量。**對帳的前置條件。**
 *
 * <p><b>兩個欄位缺一不可,而這正是一個很容易寫錯的地方。</b>
 *
 * <p>直覺會以為「pending 為 0」就等於「沒有訊息在飛」。**它不是。**
 * Redis Stream 的 {@code XPENDING} 只計算**已投遞給消費者、但尚未 ack** 的訊息;
 * 剛被 {@code XADD} 進來、消費者還沒讀走的訊息**完全不在其中**。
 *
 * <p>後果很具體:消費者一旦落後(壓測時必然如此),就會出現
 * 「pending 為 0,但串流裡還躺著幾百則訊息」的瞬間。此時對帳會看到差額、
 * 認定那些預扣已經遺失、把庫存還回去 —— 而那些訊息稍後照樣落庫成功。
 * <b>結果是真的超賣,而且只在高負載下出現。</b>
 *
 * @param pending 已投遞但未 ack 的訊息數
 * @param hasUndelivered 是否還有尚未投遞給消費者的訊息
 *     (以群組的 last-delivered-id 與串流的 last-generated-id 比對)
 */
public record StreamBacklog(long pending, boolean hasUndelivered) {

    /** 完全沒有訊息還在飛 —— **唯有此時回補才是安全的。** */
    public boolean isEmpty() {
        return pending == 0 && !hasUndelivered;
    }
}
