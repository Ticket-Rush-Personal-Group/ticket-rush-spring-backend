package com.alantsai.ticketrush.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.alantsai.ticketrush.application.port.in.PurchaseResult;
import com.alantsai.ticketrush.application.port.in.PurchaseTicketCommand;
import com.alantsai.ticketrush.application.port.out.LoadEventPort;
import com.alantsai.ticketrush.application.port.out.LoadStockPort;
import com.alantsai.ticketrush.application.port.out.SaveOrderPort;
import com.alantsai.ticketrush.application.port.out.UpdateStockPort;
import com.alantsai.ticketrush.domain.exception.EventNotFoundException;
import com.alantsai.ticketrush.domain.exception.InsufficientStockException;
import com.alantsai.ticketrush.domain.model.Event;
import com.alantsai.ticketrush.domain.model.Order;
import com.alantsai.ticketrush.domain.model.OrderStatus;
import com.alantsai.ticketrush.domain.model.Stock;
import com.alantsai.ticketrush.domain.valueobject.EventId;
import com.alantsai.ticketrush.domain.valueobject.IdempotencyKey;
import com.alantsai.ticketrush.domain.valueobject.OrderId;
import com.alantsai.ticketrush.domain.valueobject.Quantity;
import com.alantsai.ticketrush.domain.valueobject.UserId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 無鎖策略的流程單元測試(mock port,不啟動 Spring、不連資料庫)。
 *
 * <p>這裡驗證的是**單執行緒下的正確性** —— 本層在無併發時完全正確,缺陷只在併發時顯現。
 * 超賣的證據屬於 {@code OversellingEvidenceTest},以獨立 tag 隔離。
 */
@ExtendWith(MockitoExtension.class)
class NoLockPurchaseServiceTest {

    private static final EventId EVENT_ID = new EventId(7L);
    private static final Instant NOW = Instant.parse("2026-09-06T00:00:00Z");

    @Mock
    private LoadEventPort loadEventPort;

    @Mock
    private LoadStockPort loadStockPort;

    @Mock
    private UpdateStockPort updateStockPort;

    @Mock
    private SaveOrderPort saveOrderPort;

    private NoLockPurchaseService service;

    @BeforeEach
    void setUp() {
        service = new NoLockPurchaseService(
                loadEventPort, loadStockPort, updateStockPort, saveOrderPort, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private PurchaseTicketCommand command(int quantity) {
        return new PurchaseTicketCommand(
                EVENT_ID, new UserId(1L), new Quantity(quantity), new IdempotencyKey("key-001"));
    }

    private Event anEvent() {
        return new Event(EVENT_ID, "測試場次", Instant.parse("2026-12-01T12:00:00Z"), 500);
    }

    @Test
    @DisplayName("庫存足夠時扣減庫存並建立訂單")
    void deductsStockAndCreatesOrder() {
        when(loadEventPort.loadEvent(EVENT_ID)).thenReturn(Optional.of(anEvent()));
        when(loadStockPort.loadStock(EVENT_ID)).thenReturn(Optional.of(new Stock(EVENT_ID, 500, 0)));
        when(saveOrderPort.saveOrder(any())).thenAnswer(inv -> {
            Order submitted = inv.getArgument(0);
            return new Order(
                    new OrderId(42L),
                    submitted.eventId(),
                    submitted.userId(),
                    submitted.quantity(),
                    submitted.status(),
                    submitted.idempotencyKey(),
                    submitted.createdAt());
        });

        PurchaseResult result = service.purchase(command(2));

        ArgumentCaptor<Stock> written = ArgumentCaptor.forClass(Stock.class);
        verify(updateStockPort).updateStock(written.capture());
        assertThat(written.getValue().available()).isEqualTo(498);

        assertThat(result.orderId().value()).isEqualTo(42L);
        assertThat(result.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(result.quantity().value()).isEqualTo(2);
    }

    @Test
    @DisplayName("訂單的建立時間取自注入的 Clock,不是系統當下時間")
    void usesInjectedClockForCreatedAt() {
        when(loadEventPort.loadEvent(EVENT_ID)).thenReturn(Optional.of(anEvent()));
        when(loadStockPort.loadStock(EVENT_ID)).thenReturn(Optional.of(new Stock(EVENT_ID, 500, 0)));
        when(saveOrderPort.saveOrder(any())).thenAnswer(inv -> inv.getArgument(0));

        service.purchase(command(1));

        ArgumentCaptor<Order> submitted = ArgumentCaptor.forClass(Order.class);
        verify(saveOrderPort).saveOrder(submitted.capture());
        assertThat(submitted.getValue().createdAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("庫存不足時拋出例外,且不寫回庫存也不建立訂單")
    void rejectsWhenStockInsufficient() {
        when(loadEventPort.loadEvent(EVENT_ID)).thenReturn(Optional.of(anEvent()));
        when(loadStockPort.loadStock(EVENT_ID)).thenReturn(Optional.of(new Stock(EVENT_ID, 1, 0)));

        assertThatThrownBy(() -> service.purchase(command(5))).isInstanceOf(InsufficientStockException.class);

        verify(updateStockPort, never()).updateStock(any());
        verify(saveOrderPort, never()).saveOrder(any());
    }

    @Test
    @DisplayName("場次不存在時拋出例外,且不讀取庫存")
    void rejectsWhenEventMissing() {
        when(loadEventPort.loadEvent(EVENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.purchase(command(1))).isInstanceOf(EventNotFoundException.class);

        verify(loadStockPort, never()).loadStock(any());
        verify(saveOrderPort, never()).saveOrder(any());
    }

    @Test
    @DisplayName("庫存列缺失時視同場次不存在")
    void rejectsWhenStockRowMissing() {
        when(loadEventPort.loadEvent(EVENT_ID)).thenReturn(Optional.of(anEvent()));
        when(loadStockPort.loadStock(EVENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.purchase(command(1))).isInstanceOf(EventNotFoundException.class);

        verify(saveOrderPort, never()).saveOrder(any());
    }
}
