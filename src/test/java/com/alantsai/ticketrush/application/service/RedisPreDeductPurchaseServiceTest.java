package com.alantsai.ticketrush.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.alantsai.ticketrush.application.exception.EventNotOnSaleException;
import com.alantsai.ticketrush.application.port.in.PurchaseResult;
import com.alantsai.ticketrush.application.port.in.PurchaseTicketCommand;
import com.alantsai.ticketrush.application.port.out.LoadEventPort;
import com.alantsai.ticketrush.application.port.out.OrderStreamPort;
import com.alantsai.ticketrush.application.port.out.PreDeductResult;
import com.alantsai.ticketrush.application.port.out.StockCachePort;
import com.alantsai.ticketrush.domain.exception.EventNotFoundException;
import com.alantsai.ticketrush.domain.exception.InsufficientStockException;
import com.alantsai.ticketrush.domain.exception.PurchaseLimitExceededException;
import com.alantsai.ticketrush.domain.model.Event;
import com.alantsai.ticketrush.domain.policy.PurchaseLimitPolicy;
import com.alantsai.ticketrush.domain.valueobject.EventId;
import com.alantsai.ticketrush.domain.valueobject.IdempotencyKey;
import com.alantsai.ticketrush.domain.valueobject.Quantity;
import com.alantsai.ticketrush.domain.valueobject.UserId;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Redis 預扣策略的流程單元測試。
 *
 * <p><b>最重要的一條是「投遞失敗必須回補」。</b> 預扣已經發生了,而訊息沒送出去 ——
 * 那筆庫存被扣了,卻**不會有任何訊息把它變成訂單**。
 *
 * <p>更糟的是它連對帳都救不了:對帳的保守條件是「積壓為空才回補」,
 * 而一則從未被 {@code XADD} 進去的訊息**根本不在積壓裡** ——
 * 對帳會看到差額、認定它遺失、然後回補。聽起來剛好?不是:
 * 若此時服務端自己也回補了一次,庫存就被還了兩次。**兩邊都要有明確的歸屬,不能互相指望。**
 *
 * <p>{@link PurchaseLimitPolicy} 使用真實實例:它是 domain 的值物件、沒有外部依賴,
 * mock 它只會讓測試驗證不到真正的規則。
 */
@ExtendWith(MockitoExtension.class)
class RedisPreDeductPurchaseServiceTest {

    private static final EventId EVENT_ID = new EventId(7L);
    private static final UserId USER_ID = new UserId(1L);
    private static final int LIMIT = 4;

    @Mock
    private LoadEventPort loadEventPort;

    @Mock
    private StockCachePort stockCachePort;

    @Mock
    private OrderStreamPort orderStreamPort;

    private RedisPreDeductPurchaseService service;

    @BeforeEach
    void setUp() {
        service = new RedisPreDeductPurchaseService(
                loadEventPort, stockCachePort, orderStreamPort, new PurchaseLimitPolicy(LIMIT));
    }

    @Test
    @DisplayName("預扣成功 → 投遞訊息,回傳的 orderId 為 null")
    void publishesAndReturnsWithoutOrderId() {
        givenEventExists();
        when(stockCachePort.preDeduct(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(PreDeductResult.SUCCESS);

        PurchaseResult result = service.purchase(command());

        verify(orderStreamPort).publish(any());
        // orderId 為 null 不是遺漏，是事實：回應的當下訂單只是一則已受理的訊息。
        assertThat(result.orderId()).isNull();
        assertThat(result.quantity().value()).isEqualTo(1);
    }

    @Test
    @DisplayName("投遞失敗 → 回補預扣,並把例外往外拋")
    void restoresWhenPublishFails() {
        givenEventExists();
        when(stockCachePort.preDeduct(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(PreDeductResult.SUCCESS);
        doThrow(new IllegalStateException("投遞失敗")).when(orderStreamPort).publish(any());

        assertThatThrownBy(() -> service.purchase(command())).isInstanceOf(IllegalStateException.class);

        // 不回補的話：庫存被扣了，卻沒有任何訊息會把它變成訂單，
        // 而且它不在積壓裡——對帳看得到差額但無從分辨歸屬。
        verify(stockCachePort).restore(EVENT_ID, USER_ID, new Quantity(1));
    }

    @Test
    @DisplayName("場次未載入快取 → EVENT_NOT_ON_SALE,不投遞")
    void notOnSale() {
        givenEventExists();
        when(stockCachePort.preDeduct(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(PreDeductResult.NOT_ON_SALE);

        assertThatThrownBy(() -> service.purchase(command())).isInstanceOf(EventNotOnSaleException.class);

        verify(orderStreamPort, never()).publish(any());
    }

    @Test
    @DisplayName("超過限購 → PURCHASE_LIMIT_EXCEEDED,不投遞")
    void limitExceeded() {
        givenEventExists();
        when(stockCachePort.preDeduct(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(PreDeductResult.LIMIT_EXCEEDED);
        when(stockCachePort.purchasedBy(EVENT_ID, USER_ID)).thenReturn(LIMIT);

        assertThatThrownBy(() -> service.purchase(command())).isInstanceOf(PurchaseLimitExceededException.class);

        verify(orderStreamPort, never()).publish(any());
    }

    @Test
    @DisplayName("庫存不足 → INSUFFICIENT_STOCK,不投遞")
    void insufficientStock() {
        givenEventExists();
        when(stockCachePort.preDeduct(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(PreDeductResult.INSUFFICIENT_STOCK);
        when(stockCachePort.available(EVENT_ID)).thenReturn(OptionalInt.of(0));

        assertThatThrownBy(() -> service.purchase(command())).isInstanceOf(InsufficientStockException.class);

        verify(orderStreamPort, never()).publish(any());
    }

    @Test
    @DisplayName("場次不存在 → 連預扣都不做")
    void unknownEvent() {
        when(loadEventPort.loadEvent(EVENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.purchase(command())).isInstanceOf(EventNotFoundException.class);

        verify(stockCachePort, never()).preDeduct(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    private void givenEventExists() {
        when(loadEventPort.loadEvent(EVENT_ID))
                .thenReturn(Optional.of(new Event(EVENT_ID, "測試場次", Instant.parse("2026-12-01T12:00:00Z"), 500)));
    }

    private PurchaseTicketCommand command() {
        return new PurchaseTicketCommand(EVENT_ID, USER_ID, new Quantity(1), new IdempotencyKey("key-1"));
    }
}
