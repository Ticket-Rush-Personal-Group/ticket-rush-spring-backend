package com.alantsai.ticketrush.application.service;

import com.alantsai.ticketrush.application.port.in.PurchaseResult;
import com.alantsai.ticketrush.application.port.in.PurchaseTicketCommand;
import com.alantsai.ticketrush.application.port.in.PurchaseTicketUseCase;
import com.alantsai.ticketrush.application.port.out.LoadEventPort;
import com.alantsai.ticketrush.application.port.out.LoadStockPort;
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
 * 第 0 層:無鎖對照組。**本實作會超賣,而且限購也會被突破,兩者都是刻意的。**
 *
 * <p>流程為「讀取 → 於領域層計算 → 寫回」。兩個併發交易讀到相同的值、各自計算後寫回,
 * 其中一次更新就此遺失(lost update)。這個模式在本類別出現兩次:
 * 一次在限購檢查(讀已購數),一次在庫存扣減(讀可用量)——
 * **因此本層有兩個缺陷,而非一個。**
 *
 * <p><b>本類別標註 {@code @Transactional},但那不是為了正確性。</b> 四層策略的交易邊界必須一致,
 * 否則比較出來的是「有無交易」而非「有無鎖」。它同時使本層成為一個普遍誤解的反例:
 * 交易保證原子性,不保證併發下的互斥 —— PostgreSQL 預設的 READ COMMITTED 完全允許上述情境。
 *
 * <p>單執行緒下本實作完全正確。缺陷只在併發時顯現,這正是它在開發階段難以被發現的原因。
 */
@Service("noLock")
public class NoLockPurchaseService implements PurchaseTicketUseCase {

    private final LoadEventPort loadEventPort;
    private final LoadStockPort loadStockPort;
    private final UpdateStockPort updateStockPort;
    private final SaveOrderPort saveOrderPort;
    private final LoadUserPurchasedQuantityPort loadUserPurchasedQuantityPort;
    private final PurchaseLimitPolicy purchaseLimitPolicy;
    private final Clock clock;

    public NoLockPurchaseService(
            LoadEventPort loadEventPort,
            LoadStockPort loadStockPort,
            UpdateStockPort updateStockPort,
            SaveOrderPort saveOrderPort,
            LoadUserPurchasedQuantityPort loadUserPurchasedQuantityPort,
            PurchaseLimitPolicy purchaseLimitPolicy,
            Clock clock) {
        this.loadEventPort = loadEventPort;
        this.loadStockPort = loadStockPort;
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

        // 限購檢查在庫存之前。理由是失敗成本:限購只需一次索引查詢，
        // 庫存檢查則會進入鎖競爭（在第 6～8 支）。被限購擋下的請求不該去搶那把鎖。
        int alreadyPurchased = loadUserPurchasedQuantityPort.loadPurchasedQuantity(command.eventId(), command.userId());
        purchaseLimitPolicy.ensureWithinLimit(command.userId(), alreadyPurchased, command.quantity());

        Stock stock = loadStockPort
                .loadStock(command.eventId())
                .orElseThrow(() -> new EventNotFoundException(command.eventId()));

        // 領域規則在此檢查:扣減超過可用量會拋出 InsufficientStockException。
        // 但兩個併發交易可能讀到相同的 stock,各自都通過這道檢查。
        Stock deducted = stock.deduct(command.quantity());
        updateStockPort.updateStock(deducted);

        Order saved = saveOrderPort.saveOrder(Order.newOrder(
                command.eventId(), command.userId(), command.quantity(), command.idempotencyKey(), clock.instant()));

        return new PurchaseResult(saved.id(), saved.eventId(), saved.quantity(), saved.status());
    }
}
