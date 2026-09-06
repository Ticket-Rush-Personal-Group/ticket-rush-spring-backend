package com.alantsai.ticketrush.application.service;

import com.alantsai.ticketrush.application.port.in.PurchaseResult;
import com.alantsai.ticketrush.application.port.in.PurchaseTicketCommand;
import com.alantsai.ticketrush.application.port.out.CompareAndDeductStockPort;
import com.alantsai.ticketrush.application.port.out.LoadStockPort;
import com.alantsai.ticketrush.application.port.out.LoadUserPurchasedQuantityPort;
import com.alantsai.ticketrush.application.port.out.SaveOrderPort;
import com.alantsai.ticketrush.domain.exception.EventNotFoundException;
import com.alantsai.ticketrush.domain.model.Order;
import com.alantsai.ticketrush.domain.model.Stock;
import com.alantsai.ticketrush.domain.policy.PurchaseLimitPolicy;
import java.time.Clock;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 樂觀鎖的**單次**嘗試,自成一個交易。
 *
 * <p><b>本類別與 {@link OptimisticPurchaseService} 必須是兩個 bean,這不是風格選擇。</b>
 * 每一次嘗試都必須是獨立且會提交的交易 —— CAS 回傳 0 意味著對手已經提交
 * (PostgreSQL 的 UPDATE 會先卡在對手持有的列鎖上,等它提交後才重新比對 WHERE),
 * 重試若不在新的交易裡進行,就讀不到那次提交,會永遠失敗。
 *
 * <p>若把重試迴圈與本方法放進同一個類別,自我呼叫會**繞過 AOP proxy**,
 * {@code @Transactional} 完全不生效 —— 而且沒有任何錯誤訊息。跨 bean 呼叫才會經過 proxy。
 *
 * <p><b>讀取順序是正確性的一部分,不是風格問題:</b>
 *
 * <p>版本號只保護 {@code stock} 那一列,限購讀的是 {@code purchase_order} 的聚合 ——
 * 不在版本的保護範圍內。**先讀版本、再讀已購數**可保證「已購數涵蓋版本讀取當下已提交的一切」:
 * 對手若在兩次讀取之間提交,已購數讀得到它(偏保守),而 CAS 必定失敗並重試。
 * 順序相反則會超買 —— 已購數是舊的、版本是新的,CAS 會成功。
 *
 * <p>之所以這樣就夠,是因為 CAS 讓該場次的所有成交**序列化**:同一時間只有一個交易能把版本推進一格。
 *
 * <p><b>版本衝突以「回傳空」表示,不拋例外。</b> 衝突是預期中的正常結果,不是異常 ——
 * 用例外表達會讓重試迴圈變成用 catch 做流程控制,而且會與真正的失敗(庫存不足、超過限購)混在一起。
 */
@Component
public class OptimisticPurchaseAttempt {

    private final LoadStockPort loadStockPort;
    private final CompareAndDeductStockPort compareAndDeductStockPort;
    private final SaveOrderPort saveOrderPort;
    private final LoadUserPurchasedQuantityPort loadUserPurchasedQuantityPort;
    private final PurchaseLimitPolicy purchaseLimitPolicy;
    private final Clock clock;

    public OptimisticPurchaseAttempt(
            LoadStockPort loadStockPort,
            CompareAndDeductStockPort compareAndDeductStockPort,
            SaveOrderPort saveOrderPort,
            LoadUserPurchasedQuantityPort loadUserPurchasedQuantityPort,
            PurchaseLimitPolicy purchaseLimitPolicy,
            Clock clock) {
        this.loadStockPort = loadStockPort;
        this.compareAndDeductStockPort = compareAndDeductStockPort;
        this.saveOrderPort = saveOrderPort;
        this.loadUserPurchasedQuantityPort = loadUserPurchasedQuantityPort;
        this.purchaseLimitPolicy = purchaseLimitPolicy;
        this.clock = clock;
    }

    /**
     * 嘗試一次購票。
     *
     * @return 成功時為訂單結果;**版本衝突時為空**,呼叫端應重試
     * @throws com.alantsai.ticketrush.domain.exception.InsufficientStockException 庫存不足 ——
     *     這是最終結果,不得重試
     * @throws com.alantsai.ticketrush.domain.exception.PurchaseLimitExceededException 超過限購 ——
     *     同上
     */
    @Transactional
    public Optional<PurchaseResult> tryPurchase(PurchaseTicketCommand command) {
        // 1. 先讀庫存與版本。這一步必須在讀已購數之前——順序反了會超買。
        Stock stock = loadStockPort
                .loadStock(command.eventId())
                .orElseThrow(() -> new EventNotFoundException(command.eventId()));

        // 2. 再讀已購數並檢查限購。此時讀到的已購數，涵蓋了版本讀取當下所有已提交的訂單。
        purchaseLimitPolicy.ensureWithinLimit(
                command.userId(),
                loadUserPurchasedQuantityPort.loadPurchasedQuantity(command.eventId(), command.userId()),
                command.quantity());

        // 3. 領域層扣減。庫存不足在此拋出——它是最終結果，不是衝突，因此不會被重試。
        //    可用量的檢查刻意留在這裡而不放進 CAS 的 WHERE：放進去會讓 0 rows
        //    同時代表「版本衝突」與「庫存不足」，而兩者的處置相反。
        Stock deducted = stock.deduct(command.quantity());

        // 4. CAS：版本仍是我讀到的那個才寫入。0 代表對手先成交了。
        if (compareAndDeductStockPort.compareAndDeduct(deducted) == 0) {
            return Optional.empty();
        }

        // 5. 建單。與 CAS 同一交易——CAS 成功卻沒建單，等於庫存憑空消失。
        Order saved = saveOrderPort.saveOrder(Order.newOrder(
                command.eventId(), command.userId(), command.quantity(), command.idempotencyKey(), clock.instant()));

        return Optional.of(new PurchaseResult(saved.id(), saved.eventId(), saved.quantity(), saved.status()));
    }
}
