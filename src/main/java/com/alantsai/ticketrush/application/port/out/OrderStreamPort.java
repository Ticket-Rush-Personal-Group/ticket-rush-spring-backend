package com.alantsai.ticketrush.application.port.out;

import com.alantsai.ticketrush.application.port.in.PurchaseTicketCommand;

/**
 * 把已預扣的訂單投遞到訊息串流,供後續非同步落庫。**Redis 預扣策略(第 3 層)專用。**
 *
 * <p><b>與 {@link StockCachePort} 分開,不合併。</b> 前者是庫存閘門,後者是訊息投遞 ——
 * 兩者的失敗處置完全相反:預扣失敗代表「不能買」,投遞失敗代表「買到了但沒人會處理」,
 * 後者必須回補預扣。合併成一個 port 會讓這個差異消失在同一個方法裡。
 *
 * <p><b>投遞的載體必須留得住訊息。</b> 取出即離開佇列的做法(如 {@code BLPOP})在落庫前崩潰時
 * 無聲掉單且不留痕跡,而「掉單能不能補回來」正是本層要驗的東西 ——
 * 用一個會無聲掉單的載體,會分不清失敗是設計造成的還是載體造成的。
 *
 * <p>參數沿用 {@link PurchaseTicketCommand} 而非另立訊息型別:它的四個欄位正好就是落庫需要的全部,
 * 另造一個一模一樣的 record 只會多一組要同步維護的欄位。
 */
public interface OrderStreamPort {

    /**
     * 投遞一筆待落庫的訂單。
     *
     * @throws RuntimeException 投遞失敗。呼叫端 MUST 回補預扣 ——
     *     否則庫存被扣了卻沒有任何訊息會落庫,而對帳看不到 pending,
     *     會把它誤判為「已遺失」並再回補一次
     */
    void publish(PurchaseTicketCommand command);

    /**
     * 目前的積壓量:已投遞未 ack 的訊息數,以及是否還有未投遞的訊息。
     *
     * <p><b>對帳的前置條件,而不是統計數字。</b> 積壓中的訊息代表「仍會變成訂單」,
     * 它們造成的差額是暫時且正常的。不檢查就回補,會把那些庫存還回去,
     * 而訊息稍後照樣落庫成功 —— **結果是真的超賣。**
     *
     * <p>回傳 {@link StreamBacklog} 而非單一的 pending 數字,是因為
     * **「pending 為 0」不等於「沒有訊息在飛」** —— 詳見該型別的說明。
     */
    StreamBacklog backlog();
}
