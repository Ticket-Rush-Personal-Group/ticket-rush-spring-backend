package com.alantsai.ticketrush.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.alantsai.ticketrush.application.port.in.ReconciliationResult;
import com.alantsai.ticketrush.application.port.out.LoadEventSoldQuantityPort;
import com.alantsai.ticketrush.application.port.out.LoadStockPort;
import com.alantsai.ticketrush.application.port.out.OrderStreamPort;
import com.alantsai.ticketrush.application.port.out.StockCachePort;
import com.alantsai.ticketrush.application.port.out.StreamBacklog;
import com.alantsai.ticketrush.domain.model.Stock;
import com.alantsai.ticketrush.domain.valueobject.EventId;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 對帳的決策表。
 *
 * <p><b>這裡刻意用 mock 而非真實 Redis:要驗的是「在什麼條件下回補、什麼條件下不回補」,
 * 而 pending 的數量在真實環境裡很難精確擺佈。</b> 決策本身是純邏輯,用 mock 才能把
 * 每一種條件組合都測到 —— 尤其是「有差額但 pending 非空」這個最重要、也最難自然重現的組合。
 *
 * <p>回補的正確性(數字有沒有真的加回去)由整合測試負責。
 */
@ExtendWith(MockitoExtension.class)
class ReconcileStockServiceTest {

    private static final EventId EVENT_ID = new EventId(7L);
    private static final int ALLOCATED = 500;

    @Mock
    private StockCachePort stockCachePort;

    @Mock
    private LoadStockPort loadStockPort;

    @Mock
    private LoadEventSoldQuantityPort loadEventSoldQuantityPort;

    @Mock
    private OrderStreamPort orderStreamPort;

    @InjectMocks
    private ReconcileStockService service;

    @Test
    @DisplayName("有差額且 pending 為空 → 回補差額")
    void restoresWhenPendingIsEmpty() {
        // 已預扣 10（500 → 490），已落庫 7，差額 3。
        given(490, 7, 0);

        ReconciliationResult result = service.reconcile(EVENT_ID);

        assertThat(result.preDeducted()).isEqualTo(10);
        assertThat(result.discrepancy()).isEqualTo(3);
        assertThat(result.restored()).isTrue();
        verify(stockCachePort).restoreStockOnly(EVENT_ID, 3);
    }

    @Test
    @DisplayName("有差額但積壓非空 → 不回補 —— 本測試是整個對帳最重要的一條")
    void doesNotRestoreWhilePendingIsNotEmpty() {
        // 同樣的差額 3，但還有 3 則訊息在飛——它們就是那個差額的來源。
        given(490, 7, 3);

        ReconciliationResult result = service.reconcile(EVENT_ID);

        assertThat(result.discrepancy()).isEqualTo(3);
        assertThat(result.restored()).as("積壓非空時不得回補").isFalse();
        // 此時回補，那 3 則訊息稍後落庫成功，庫存就被還了兩次——**那是真的超賣**。
        verify(stockCachePort, never()).restoreStockOnly(EVENT_ID, 3);
    }

    @Test
    @DisplayName("pending 為 0 但仍有未投遞的訊息 → 不得回補")
    void doesNotRestoreWhenUndeliveredEntriesRemain() {
        when(stockCachePort.available(EVENT_ID)).thenReturn(OptionalInt.of(490));
        when(loadStockPort.loadStock(EVENT_ID)).thenReturn(Optional.of(new Stock(EVENT_ID, ALLOCATED, 0)));
        when(loadEventSoldQuantityPort.loadSoldQuantity(EVENT_ID)).thenReturn(7);
        // XPENDING 只算「已投遞未 ack」的訊息。消費者一落後，就會出現
        // 「pending 為 0，但串流裡還躺著好幾則沒被讀走」的瞬間——
        // **只看 pending 的話，這裡就會誤補，而誤補的結果是超賣。**
        when(orderStreamPort.backlog()).thenReturn(new StreamBacklog(0, true));

        ReconciliationResult result = service.reconcile(EVENT_ID);

        assertThat(result.discrepancy()).isEqualTo(3);
        assertThat(result.restored()).as("有未投遞的訊息時不得回補").isFalse();
        verify(stockCachePort, never()).restoreStockOnly(EVENT_ID, 3);
    }

    @Test
    @DisplayName("沒有差額 → 不回補")
    void doesNotRestoreWithoutDiscrepancy() {
        given(490, 10, 0);

        ReconciliationResult result = service.reconcile(EVENT_ID);

        assertThat(result.discrepancy()).isZero();
        assertThat(result.converged()).isTrue();
        verify(stockCachePort, never()).restoreStockOnly(EVENT_ID, 0);
    }

    @Test
    @DisplayName("訂單多於扣減 → 不回補,這是比差額嚴重得多的異常")
    void doesNotRestoreWhenSoldExceedsPreDeducted() {
        // 落庫 20 張但只扣了 10——代表有訂單未經預扣就進了資料庫。
        given(490, 20, 0);

        ReconciliationResult result = service.reconcile(EVENT_ID);

        assertThat(result.discrepancy()).isNegative();
        assertThat(result.restored()).isFalse();
        // 回補會讓庫存憑空增加，把一個資料正確性問題變成兩個。
        verify(stockCachePort, never()).restoreStockOnly(org.mockito.ArgumentMatchers.eq(EVENT_ID), anyInt());
    }

    @Test
    @DisplayName("快取未載入該場次 → 安靜略過")
    void skipsEventWithoutCachedStock() {
        when(stockCachePort.available(EVENT_ID)).thenReturn(OptionalInt.empty());
        when(loadStockPort.loadStock(EVENT_ID)).thenReturn(Optional.of(new Stock(EVENT_ID, ALLOCATED, 0)));

        ReconciliationResult result = service.reconcile(EVENT_ID);

        // 對帳是背景工作，遇到不參與第 3 層的場次應該安靜略過，不是拋例外。
        assertThat(result.converged()).isTrue();
        verify(stockCachePort, never()).restoreStockOnly(org.mockito.ArgumentMatchers.eq(EVENT_ID), anyInt());
    }

    private void given(int cachedAvailable, int sold, long pending) {
        // 積壓包含「已投遞未 ack」與「尚未投遞」兩部分；此處只擺佈前者即可測到決策。
        when(stockCachePort.available(EVENT_ID)).thenReturn(OptionalInt.of(cachedAvailable));
        when(loadStockPort.loadStock(EVENT_ID)).thenReturn(Optional.of(new Stock(EVENT_ID, ALLOCATED, 0)));
        when(loadEventSoldQuantityPort.loadSoldQuantity(EVENT_ID)).thenReturn(sold);
        when(orderStreamPort.backlog()).thenReturn(new StreamBacklog(pending, false));
    }
}
