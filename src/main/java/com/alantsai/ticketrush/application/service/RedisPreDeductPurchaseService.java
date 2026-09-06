package com.alantsai.ticketrush.application.service;

import com.alantsai.ticketrush.application.exception.EventNotOnSaleException;
import com.alantsai.ticketrush.application.port.in.PurchaseResult;
import com.alantsai.ticketrush.application.port.in.PurchaseTicketCommand;
import com.alantsai.ticketrush.application.port.in.PurchaseTicketUseCase;
import com.alantsai.ticketrush.application.port.out.LoadEventPort;
import com.alantsai.ticketrush.application.port.out.OrderStreamPort;
import com.alantsai.ticketrush.application.port.out.PreDeductResult;
import com.alantsai.ticketrush.application.port.out.StockCachePort;
import com.alantsai.ticketrush.domain.exception.EventNotFoundException;
import com.alantsai.ticketrush.domain.exception.InsufficientStockException;
import com.alantsai.ticketrush.domain.exception.PurchaseLimitExceededException;
import com.alantsai.ticketrush.domain.model.OrderStatus;
import com.alantsai.ticketrush.domain.policy.PurchaseLimitPolicy;
import org.springframework.stereotype.Service;

/**
 * 第 3 層:Redis 預扣。**唯一一個回應時訂單還不存在的策略。**
 *
 * <p>前三層都在回答「**在資料庫裡**要怎麼處理競爭」。本層改問「如果根本不讓競爭進到資料庫呢」——
 * 把庫存搬到 Redis 當閘門,請求在記憶體裡就決定成敗,訂單非同步落庫。
 *
 * <p><b>本類別刻意不標 {@code @Transactional}。</b> 不是忘了 —— Redis 不參與資料庫交易,
 * 而本層在回應之前根本不碰資料庫。硬套一個交易,會得到一個包住一連串 Redis 操作的空轉交易,
 * 白白佔用連線。這是 {@code backend-architecture.md} 早已定調的事。
 *
 * <p><b>Redis 是准入控制,資料庫是最終帳本,兩者不對等。</b> Redis 決定「誰可以買」,
 * 資料庫記錄「誰買到了」。因此**超賣的定義完全不變** —— 仍以資料庫的訂單張數為準。
 * Redis 已扣但尚未落庫的部分不是超賣,是待收斂的差額。
 *
 * <p><b>限購的執行位置在本層移進了 Lua 腳本。</b> 這是刻意的:留在應用端就是 check-then-act,
 * 而實測顯示那在 Redis 上比在資料庫上失效得更徹底 —— 儲存體越快,
 * 「讀取」與「寫入」之間的窗口就越接近「全部同時」。
 *
 * <p>回傳的 {@link PurchaseResult} 其 {@code orderId} 為 {@code null} ——
 * 那不是遺漏,是事實:回應的當下訂單只是一則已受理的訊息。
 */
@Service("redisPreDeduct")
public class RedisPreDeductPurchaseService implements PurchaseTicketUseCase {

    private final LoadEventPort loadEventPort;
    private final StockCachePort stockCachePort;
    private final OrderStreamPort orderStreamPort;
    private final PurchaseLimitPolicy purchaseLimitPolicy;

    public RedisPreDeductPurchaseService(
            LoadEventPort loadEventPort,
            StockCachePort stockCachePort,
            OrderStreamPort orderStreamPort,
            PurchaseLimitPolicy purchaseLimitPolicy) {
        this.loadEventPort = loadEventPort;
        this.stockCachePort = stockCachePort;
        this.orderStreamPort = orderStreamPort;
        this.purchaseLimitPolicy = purchaseLimitPolicy;
    }

    @Override
    public PurchaseResult purchase(PurchaseTicketCommand command) {
        loadEventPort.loadEvent(command.eventId()).orElseThrow(() -> new EventNotFoundException(command.eventId()));

        // 限購與庫存的檢查、以及扣減，三件事在單一 Lua 腳本內原子完成。
        // 上限由此處傳入而非讓 adapter 自己去讀——限購的執行位置被移進了 Redis，
        // 那是架構上的事實，應該在呼叫處看得見。
        PreDeductResult result = stockCachePort.preDeduct(
                command.eventId(), command.userId(), command.quantity(), purchaseLimitPolicy.maxTicketsPerUser());

        switch (result) {
            case NOT_ON_SALE -> throw new EventNotOnSaleException(command.eventId());
            case LIMIT_EXCEEDED ->
                throw new PurchaseLimitExceededException(
                        command.userId(),
                        stockCachePort.purchasedBy(command.eventId(), command.userId()),
                        command.quantity().value(),
                        purchaseLimitPolicy.maxTicketsPerUser());
            case INSUFFICIENT_STOCK ->
                throw new InsufficientStockException(
                        command.eventId(),
                        stockCachePort.available(command.eventId()).orElse(0),
                        command.quantity().value());
            case SUCCESS -> {
                // 繼續。
            }
        }

        try {
            orderStreamPort.publish(command);
        } catch (RuntimeException e) {
            // 投遞失敗必須回補。否則庫存被扣了，卻沒有任何訊息會落庫——
            // 而對帳看不到 pending，會把這個差額誤判為「已遺失」並再回補一次，形成兩次回補。
            stockCachePort.restore(command.eventId(), command.userId(), command.quantity());
            throw e;
        }

        // orderId 為 null：回應的當下訂單確實還不存在。
        // controller 依此決定回 202 而非 201——它據以判斷的是「訂單建立了沒」這個事實，
        // 不是「現在是哪一個策略」。策略的身分不得洩漏到 adapter 層。
        return new PurchaseResult(null, command.eventId(), command.quantity(), OrderStatus.PENDING);
    }
}
