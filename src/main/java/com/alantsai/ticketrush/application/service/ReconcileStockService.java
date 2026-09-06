package com.alantsai.ticketrush.application.service;

import com.alantsai.ticketrush.application.port.in.ReconcileStockUseCase;
import com.alantsai.ticketrush.application.port.in.ReconciliationResult;
import com.alantsai.ticketrush.application.port.out.LoadEventSoldQuantityPort;
import com.alantsai.ticketrush.application.port.out.LoadStockPort;
import com.alantsai.ticketrush.application.port.out.OrderStreamPort;
import com.alantsai.ticketrush.application.port.out.StockCachePort;
import com.alantsai.ticketrush.application.port.out.StreamBacklog;
import com.alantsai.ticketrush.domain.model.Stock;
import com.alantsai.ticketrush.domain.valueobject.EventId;
import java.util.List;
import java.util.OptionalInt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 對帳。**本層最容易寫錯的地方,而且錯的方向是超賣。**
 *
 * <p>差額的定義:
 *
 * <pre>
 *   初始配額  = 資料庫 stock.available   （第 3 層不扣減它，它保留為配額）
 *   已預扣    = 初始配額 − 快取餘量
 *   差額      = 已預扣 − 資料庫已記錄的售出張數
 * </pre>
 *
 * <p><b>{@code pending 為空} 是回補的必要條件,這是本類別的核心。</b>
 *
 * <p>pending 中的訊息代表「仍在處理」——它們造成的差額是**暫時且正常的**。
 * 不看 pending 就回補,會把處理中訂單所佔的庫存還回去,而那些訂單稍後落庫成功
 * —— <b>結果是真的超賣。</b>
 *
 * <blockquote>
 * <b>對帳真正的危險不是漏補,是誤補。</b> 漏補的代價是少賣幾張票;
 * 誤補的代價是超賣 —— 而超賣正是整個專案要消滅的東西。兩者不對等,因此對帳一律偏保守。
 * </blockquote>
 *
 * <p><b>刻意不以「把快取覆寫成 初始配額 − 資料庫訂單數」的方式對帳。</b>
 * 那是誤補的最極端形式(等於假設沒有任何訂單在飛),而且覆寫是絕對值寫入,
 * 會與併發中的預扣互相踩踏 —— 兩個問題疊在一起。回補一律是增量。
 */
@Service
public class ReconcileStockService implements ReconcileStockUseCase {

    private static final Logger log = LoggerFactory.getLogger(ReconcileStockService.class);

    private final StockCachePort stockCachePort;
    private final LoadStockPort loadStockPort;
    private final LoadEventSoldQuantityPort loadEventSoldQuantityPort;
    private final OrderStreamPort orderStreamPort;

    public ReconcileStockService(
            StockCachePort stockCachePort,
            LoadStockPort loadStockPort,
            LoadEventSoldQuantityPort loadEventSoldQuantityPort,
            OrderStreamPort orderStreamPort) {
        this.stockCachePort = stockCachePort;
        this.loadStockPort = loadStockPort;
        this.loadEventSoldQuantityPort = loadEventSoldQuantityPort;
        this.orderStreamPort = orderStreamPort;
    }

    @Override
    public List<ReconciliationResult> reconcileAll() {
        return stockCachePort.eventsOnSale().stream().map(this::reconcile).toList();
    }

    @Override
    public ReconciliationResult reconcile(EventId eventId) {
        OptionalInt cached = stockCachePort.available(eventId);
        int allocated = loadStockPort.loadStock(eventId).map(Stock::available).orElse(0);

        // 快取沒載入就沒有第 3 層的活動可對。回傳一筆「已收斂」而非拋例外——
        // 對帳是背景工作，遇到不相干的場次應該安靜略過。
        if (cached.isEmpty()) {
            return new ReconciliationResult(eventId, 0, 0, 0, new StreamBacklog(0, false), false);
        }

        int preDeducted = allocated - cached.getAsInt();
        int sold = loadEventSoldQuantityPort.loadSoldQuantity(eventId);
        int discrepancy = preDeducted - sold;
        StreamBacklog backlog = orderStreamPort.backlog();

        if (discrepancy < 0) {
            // 訂單比扣減還多。這不該發生——若發生，代表有訂單未經預扣就進了資料庫，
            // 那是比差額嚴重得多的問題。**不回補**（回補會讓它更糟），只大聲記錄。
            log.error("對帳異常:場次 {} 的訂單張數({})多於快取扣減量({})—— 有訂單未經預扣", eventId.value(), sold, preDeducted);
            return new ReconciliationResult(eventId, preDeducted, sold, discrepancy, backlog, false);
        }

        if (discrepancy == 0) {
            return new ReconciliationResult(eventId, preDeducted, sold, 0, backlog, false);
        }

        if (!backlog.isEmpty()) {
            // 差額存在，但有訊息還在飛——這個差額是暫時的，它會自己消失。
            // **此時回補就是誤補，而誤補的結果是超賣。**
            //
            // 判斷用的是 backlog 而不是單看 pending：XPENDING 只算「已投遞未 ack」的，
            // 剛 XADD 進來、消費者還沒讀走的訊息完全不在其中。消費者一落後
            // （壓測時必然如此）就會出現「pending 為 0 但串流裡還躺著幾百則」的瞬間。
            log.debug(
                    "場次 {} 有差額 {} 張,但仍有積壓(pending {}、未投遞 {}),暫不回補",
                    eventId.value(),
                    discrepancy,
                    backlog.pending(),
                    backlog.hasUndelivered());
            return new ReconciliationResult(eventId, preDeducted, sold, discrepancy, backlog, false);
        }

        // pending 為空且仍有差額：那些預扣確實遺失了，沒有任何訊息會把它們變成訂單。
        //
        // 只回補庫存，不回補任何人的已購數——對帳不知道那些扣減屬於誰。
        // 那些使用者的限購額度仍被佔用著（他們會少買到票），這是刻意接受的保守。
        stockCachePort.restoreStockOnly(eventId, discrepancy);
        log.info("場次 {} 回補 {} 張:已預扣 {}、已售出 {}、積壓為空", eventId.value(), discrepancy, preDeducted, sold);

        return new ReconciliationResult(eventId, preDeducted, sold, discrepancy, backlog, true);
    }
}
