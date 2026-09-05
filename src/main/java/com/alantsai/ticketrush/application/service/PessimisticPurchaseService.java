package com.alantsai.ticketrush.application.service;

import com.alantsai.ticketrush.application.port.in.PurchaseResult;
import com.alantsai.ticketrush.application.port.in.PurchaseTicketCommand;
import com.alantsai.ticketrush.application.port.in.PurchaseTicketUseCase;
import com.alantsai.ticketrush.application.port.out.LoadEventPort;
import com.alantsai.ticketrush.application.port.out.LoadStockForUpdatePort;
import com.alantsai.ticketrush.application.port.out.LoadUserPurchasedQuantityPort;
import com.alantsai.ticketrush.application.port.out.SaveOrderPort;
import com.alantsai.ticketrush.application.port.out.UpdateStockPort;
import com.alantsai.ticketrush.domain.exception.EventNotFoundException;
import com.alantsai.ticketrush.domain.model.Order;
import com.alantsai.ticketrush.domain.model.Stock;
import com.alantsai.ticketrush.domain.policy.PurchaseLimitPolicy;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 第 1 層:悲觀鎖。**第一個真正正確的實作。**
 *
 * <p>以 {@code SELECT ... FOR UPDATE} 取得庫存列的排他鎖,使該場次的購票流程序列化。
 * 鎖在交易提交時才釋放,因此下一個交易讀得到前一個的所有寫入 ——
 * 這正是第 3 支發現的「{@code synchronized} 無效」的反面:那時鎖比交易窄,現在交易在鎖之內。
 *
 * <p><b>限購檢查執行兩次,這是刻意的:</b>
 *
 * <ol>
 *   <li><b>鎖前快篩</b> —— 明顯超限的請求在此快速失敗,不進入鎖競爭。
 *       限購的目的之一就是擋掉大量無效請求,讓它們全部排隊等鎖等於自廢武功。
 *   <li><b>鎖後權威檢查</b> —— 此時才在鎖的保護範圍內。**只做快篩的話限購仍會被突破**,
 *       第 5 支的證據(超買 6 張)會原封不動重現。
 * </ol>
 *
 * <p>代價是多一次查詢,但它落在索引上,且只發生在通過快篩的請求上。
 *
 * <p>本層的定位是「正確但慢」。它的吞吐明顯低於第 0 層 —— 那是預期且正確的結果,
 * 不得為了讓數字好看而調整測量條件。
 */
@Service("pessimistic")
public class PessimisticPurchaseService implements PurchaseTicketUseCase {

    private final LoadEventPort loadEventPort;
    private final LoadStockForUpdatePort loadStockForUpdatePort;
    private final UpdateStockPort updateStockPort;
    private final SaveOrderPort saveOrderPort;
    private final LoadUserPurchasedQuantityPort loadUserPurchasedQuantityPort;
    private final PurchaseLimitPolicy purchaseLimitPolicy;
    private final Clock clock;

    public PessimisticPurchaseService(
            LoadEventPort loadEventPort,
            LoadStockForUpdatePort loadStockForUpdatePort,
            UpdateStockPort updateStockPort,
            SaveOrderPort saveOrderPort,
            LoadUserPurchasedQuantityPort loadUserPurchasedQuantityPort,
            PurchaseLimitPolicy purchaseLimitPolicy,
            Clock clock) {
        this.loadEventPort = loadEventPort;
        this.loadStockForUpdatePort = loadStockForUpdatePort;
        this.updateStockPort = updateStockPort;
        this.saveOrderPort = saveOrderPort;
        this.loadUserPurchasedQuantityPort = loadUserPurchasedQuantityPort;
        this.purchaseLimitPolicy = purchaseLimitPolicy;
        this.clock = clock;
    }

    @Override
    @Transactional
    public PurchaseResult purchase(PurchaseTicketCommand command) {
        loadEventPort.loadEvent(command.eventId()).orElseThrow(() -> new EventNotFoundException(command.eventId()));

        // 1. 鎖前快篩：讓明顯超限的請求不必進入鎖競爭。
        //    這一步不保證正確——它不在鎖的保護範圍內。
        purchaseLimitPolicy.ensureWithinLimit(
                command.userId(),
                loadUserPurchasedQuantityPort.loadPurchasedQuantity(command.eventId(), command.userId()),
                command.quantity());

        // 2. 取得排他鎖。此後直到交易提交為止，該場次的購票流程被序列化。
        Stock stock = loadStockForUpdatePort
                .loadStockForUpdate(command.eventId())
                .orElseThrow(() -> new EventNotFoundException(command.eventId()));

        // 3. 鎖後權威檢查：此時讀到的已購數包含所有已提交的交易。
        //    **移除這一步，限購就會被突破**——鎖前的快篩擋不住併發。
        purchaseLimitPolicy.ensureWithinLimit(
                command.userId(),
                loadUserPurchasedQuantityPort.loadPurchasedQuantity(command.eventId(), command.userId()),
                command.quantity());

        // 4. 扣減與建單，全程在鎖的保護之下。
        Stock deducted = stock.deduct(command.quantity());
        updateStockPort.updateStock(deducted);

        Order saved = saveOrderPort.saveOrder(Order.newOrder(
                command.eventId(), command.userId(), command.quantity(), command.idempotencyKey(), clock.instant()));

        return new PurchaseResult(saved.id(), saved.eventId(), saved.quantity(), saved.status());
    }
}
