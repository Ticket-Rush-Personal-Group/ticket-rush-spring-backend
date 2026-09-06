package com.alantsai.ticketrush.application.port.out;

import com.alantsai.ticketrush.domain.valueobject.EventId;
import com.alantsai.ticketrush.domain.valueobject.Quantity;
import com.alantsai.ticketrush.domain.valueobject.UserId;
import java.util.OptionalInt;
import java.util.Set;

/**
 * 快取層的庫存閘門。**Redis 預扣策略(第 3 層)專用。**
 *
 * <p>本 port 代表的是**准入控制**,不是帳本。它決定「誰可以買」,而「誰買到了」由資料庫記錄 ——
 * 兩者不對等,超賣的定義始終以資料庫為準。
 *
 * <p><b>限購上限由呼叫端傳入,不由 adapter 自行取得。</b> 上限是領域政策
 * ({@code PurchaseLimitPolicy})的值,讓 adapter 去讀它會使政策有兩個入口。
 * 更重要的是:**限購的執行位置在本層被移進了 Redis 腳本**,這是一件架構上的事實,
 * 應該在 service 的呼叫處看得見,而不是藏在 adapter 裡。
 *
 * @see LoadStockForUpdatePort 第 1 層的做法:鎖住資料庫的列,讓後來者排隊
 * @see CompareAndDeductStockPort 第 2 層的做法:偵測版本衝突後重做
 */
public interface StockCachePort {

    /**
     * 原子完成「限購檢查 + 庫存檢查 + 扣減」。
     *
     * <p>三件事 MUST 在單一 Lua 腳本內完成。若限購檢查留在應用端(讀取後判斷再扣減),
     * 那就是 check-then-act —— 兩個併發請求會讀到相同的已購數並各自通過檢查,
     * 與第 0 層的缺陷同源。
     *
     * @param eventId 場次
     * @param userId 購買者
     * @param quantity 本次購買張數
     * @param maxTicketsPerUser 單人限購上限,由 {@code PurchaseLimitPolicy} 提供
     * @return 四種結果之一;僅 {@link PreDeductResult#SUCCESS} 表示庫存已被扣減
     */
    PreDeductResult preDeduct(EventId eventId, UserId userId, Quantity quantity, int maxTicketsPerUser);

    /**
     * 回補一筆預扣:庫存加回、已購數減回。
     *
     * <p>兩者 MUST 原子完成。分兩次呼叫的話,中間有一段「庫存已加回、已購數還沒減」的時間 ——
     * 該使用者的限購額度在那個瞬間被錯誤地佔用著,而他可能正好在重試。
     *
     * <p>呼叫時機有二:投遞訊息失敗(預扣已發生但沒有任何訊息會落庫),以及落庫失敗的補償。
     */
    void restore(EventId eventId, UserId userId, Quantity quantity);

    /**
     * 只回補庫存,不回補任何使用者的已購數。**對帳專用。**
     *
     * <p>對帳算出的是**聚合差額** —— 它知道「有幾張票被扣了卻沒有訂單」,
     * 但不知道那些扣減屬於誰。因此只能把庫存還回去,無法還原限購額度。
     *
     * <p><b>這個不對稱是刻意接受的代價,而且方向是安全的:</b>
     * 那些使用者的限購額度仍被佔用著,他們會少買到票 —— 保守。
     * 反過來(猜測歸屬並回補已購數)則可能讓某人超過限購,那是不可接受的。
     * **對帳寧可漏補,不可誤補。**
     *
     * @see #restore(EventId, UserId, Quantity) 歸屬已知時的完整回補
     */
    void restoreStockOnly(EventId eventId, int quantity);

    /**
     * 目前的快取餘量。
     *
     * <p>用於對帳(差額 = 初始庫存 − 餘量 − 資料庫訂單張數)與錯誤訊息。
     *
     * @return 餘量;場次未載入時為空
     */
    OptionalInt available(EventId eventId);

    /**
     * 目前已載入快取庫存的所有場次 —— 也就是「正在開賣中」的場次。
     *
     * <p>對帳的對象。沒有載入快取的場次不參與第 3 層,也就沒有差額可言。
     */
    Set<EventId> eventsOnSale();

    /**
     * 該場次的庫存 key 是否設有過期時間。
     *
     * <p><b>用於讓「忘記設過期時間」這件事被看見,而不是替呼叫端做決定。</b>
     * 缺少過期時間的後果是無界的記憶體成長 —— 而它不會使任何測試失敗、
     * 不會產生錯誤訊息、也不會出現在壓測中。
     */
    boolean hasExpiry(EventId eventId);

    /**
     * 某使用者在某場次的已購張數。
     *
     * <p><b>僅供錯誤訊息使用,不得作為判斷依據。</b> 限購的判斷已在 Lua 腳本內原子完成;
     * 此處讀到的值是事後的快照,與當時的決策之間可能已有其他請求成交。
     */
    int purchasedBy(EventId eventId, UserId userId);
}
