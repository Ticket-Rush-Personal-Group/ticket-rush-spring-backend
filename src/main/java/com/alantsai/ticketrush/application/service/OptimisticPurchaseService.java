package com.alantsai.ticketrush.application.service;

import com.alantsai.ticketrush.application.exception.RetryExhaustedException;
import com.alantsai.ticketrush.application.metrics.RetryStatistics;
import com.alantsai.ticketrush.application.port.in.PurchaseResult;
import com.alantsai.ticketrush.application.port.in.PurchaseTicketCommand;
import com.alantsai.ticketrush.application.port.in.PurchaseTicketUseCase;
import com.alantsai.ticketrush.application.port.out.LoadEventPort;
import com.alantsai.ticketrush.domain.exception.EventNotFoundException;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 第 2 層:樂觀鎖。**不阻擋競爭,而是偵測它。**
 *
 * <p>以條件式 UPDATE(`WHERE version = ?`)判斷「我讀到之後有沒有人先動過」。
 * 沒有就成交,有就重來 —— 沒有任何請求需要等待別人。
 *
 * <p><b>本類別刻意不標 {@code @Transactional},這是本層最關鍵的一件事。</b>
 * 重試迴圈必須在交易之外:
 *
 * <ul>
 *   <li><b>正確性</b> —— 每次嘗試必須是獨立且已提交的交易,否則讀不到對手的寫入,重試永遠失敗。
 *   <li><b>效能</b> —— 迴圈若包在交易裡,連線會被佔用整個重試期間。1000 併發、連線池 50,
 *       重試風暴會直接把連線池吃乾,量到的又是「等連線」而不是「重試」。
 *       第 6 支已經證明這兩者在延遲圖表上分不出來。
 * </ul>
 *
 * <p>由 ArchUnit 守則強制(見 {@code HexagonalLayeringTest}),因為它一旦寫錯不會有任何錯誤訊息。
 *
 * <p><b>刻意不加退避(backoff)。</b> 重試風暴正是本層要量的現象,加退避等於把它蓋掉 ——
 * 退避幾乎確定會讓數字變好看,但它改善的是一個還沒被量化的問題。
 *
 * <p><b>樂觀鎖的前提是「衝突很少」,而搶票正是這個前提最不成立的形狀。</b>
 * 本層的定位不是「更好的悲觀鎖」,而是示範一個正確的機制在錯誤的場景下如何失效 ——
 * 失效的形態是重試風暴與成交率下降,而不是資料錯誤。
 */
@Service("optimistic")
public class OptimisticPurchaseService implements PurchaseTicketUseCase {

    private final LoadEventPort loadEventPort;
    private final OptimisticPurchaseAttempt attempt;
    private final RetryStatistics retryStatistics;
    private final int maxAttempts;

    public OptimisticPurchaseService(
            LoadEventPort loadEventPort,
            OptimisticPurchaseAttempt attempt,
            RetryStatistics retryStatistics,
            @Value("${ticket-rush.optimistic.max-attempts}") int maxAttempts) {
        this.loadEventPort = loadEventPort;
        this.attempt = attempt;
        this.retryStatistics = retryStatistics;
        this.maxAttempts = maxAttempts;
    }

    @Override
    public PurchaseResult purchase(PurchaseTicketCommand command) {
        // 場次是否存在只查一次，不放進重試迴圈。
        // 放進去的話，重試風暴會把這個查詢放大 N 倍——那是實作造成的額外負載，
        // 會混進本層要量測的吞吐裡。場次存在與否在一次購票期間不會改變。
        loadEventPort.loadEvent(command.eventId()).orElseThrow(() -> new EventNotFoundException(command.eventId()));

        int attempts = 0;
        try {
            while (attempts < maxAttempts) {
                attempts++;
                Optional<PurchaseResult> result = attempt.tryPurchase(command);
                if (result.isPresent()) {
                    return result.get();
                }
            }
        } finally {
            // 放在 finally：庫存不足與超過限購也要記，而它們是以例外離開迴圈的。
            // 只記成功的會讓分佈失真——售罄後被快速拒絕的請求嘗試次數是 1，
            // 把它們排除會同時高估平均與尾端。
            retryStatistics.recordAttempts(attempts);
        }

        // 重試耗盡：**有票，但在版本競爭中連續搶輸到達上限。**
        // 這是本層特有的失敗模式，不是「沒票了」——耗盡率是量測項目，不是要被消除的錯誤。
        retryStatistics.recordExhaustion();
        throw new RetryExhaustedException(command.eventId(), attempts);
    }
}
