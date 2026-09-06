package com.alantsai.ticketrush.application.port.in;

/**
 * 把一筆已在快取預扣成功的訂單落庫。**Redis 預扣策略(第 3 層)的非同步後半段。**
 *
 * <p><b>新增 in port 而非重用 {@link PurchaseTicketUseCase}。</b> 落庫不是「購票」——
 * 購票的決策(能不能買)已經在預扣時完成了,這裡只是把既成事實寫進帳本。
 * 把它塞進同一個 in port 會讓 {@code PurchaseFacade} 的
 * {@code Map<String, PurchaseTicketUseCase>} 混入不是策略的東西,
 * 而那個 Map 正是「同一個 API、四種實作」的載體。
 *
 * <p>結果分為「已落庫」與「重複」,而非布林值 —— 呼叫端對兩者的處置不同:
 * 前者是正常完成,後者代表訊息被重送過,**兩者都不得回補庫存**,
 * 但只有後者需要被記錄下來(它是崩潰窗口確實發生過的證據)。
 */
public interface PersistPendingOrderUseCase {

    /**
     * 落庫。
     *
     * @param command 預扣時的購票指令,四個欄位即落庫所需的全部
     * @return {@link PersistOutcome#PERSISTED} 或 {@link PersistOutcome#DUPLICATE}
     * @throws RuntimeException 落庫失敗。呼叫端 MUST 回補快取的預扣 ——
     *     那筆庫存已經被扣掉,而永遠不會有對應的訂單
     */
    PersistOutcome persist(PurchaseTicketCommand command);
}
