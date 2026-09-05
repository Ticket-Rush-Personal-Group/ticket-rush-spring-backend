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
import com.alantsai.ticketrush.application.port.out.LoadUserPurchasedQuantityPort;
import com.alantsai.ticketrush.application.port.out.SaveOrderPort;
import com.alantsai.ticketrush.application.port.out.UpdateStockPort;
import com.alantsai.ticketrush.domain.exception.EventNotFoundException;
import com.alantsai.ticketrush.domain.exception.InsufficientStockException;
import com.alantsai.ticketrush.domain.exception.PurchaseLimitExceededException;
import com.alantsai.ticketrush.domain.model.Event;
import com.alantsai.ticketrush.domain.model.Order;
import com.alantsai.ticketrush.domain.model.OrderStatus;
import com.alantsai.ticketrush.domain.model.Stock;
import com.alantsai.ticketrush.domain.policy.PurchaseLimitPolicy;
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
 * 超賣與限購突破的證據屬於 {@code strategy} 套件下的證據測試,以獨立 tag 隔離。
 *
 * <p>{@link PurchaseLimitPolicy} 使用真實實例而非 mock:它是 domain 的值物件、沒有外部依賴,
 * mock 它只會讓測試驗證不到真正的規則。**只 mock 跨越邊界的東西。**
 */
@ExtendWith(MockitoExtension.class)
class NoLockPurchaseServiceTest {

    private static final EventId EVENT_ID = new EventId(7L);
    private static final UserId USER_ID = new UserId(1L);
    private static final Instant NOW = Instant.parse("2026-09-06T00:00:00Z");
    private static final int LIMIT = 4;

    @Mock
    private LoadEventPort loadEventPort;

    @Mock
    private LoadStockPort loadStockPort;

    @Mock
    private UpdateStockPort updateStockPort;

    @Mock
    private SaveOrderPort saveOrderPort;

    @Mock
    private LoadUserPurchasedQuantityPort loadUserPurchasedQuantityPort;

    private NoLockPurchaseService service;

    @BeforeEach
    void setUp() {
        service = new NoLockPurchaseService(
                loadEventPort,
                loadStockPort,
                updateStockPort,
                saveOrderPort,
                loadUserPurchasedQuantityPort,
                new PurchaseLimitPolicy(LIMIT),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private PurchaseTicketCommand command(int quantity) {
        return new PurchaseTicketCommand(EVENT_ID, USER_ID, new Quantity(quantity), new IdempotencyKey("key-001"));
    }

    private Event anEvent() {
        return new Event(EVENT_ID, "測試場次", Instant.parse("2026-12-01T12:00:00Z"), 500);
    }

    private void givenEventExists() {
        when(loadEventPort.loadEvent(EVENT_ID)).thenReturn(Optional.of(anEvent()));
    }

    private void givenAlreadyPurchased(int quantity) {
        when(loadUserPurchasedQuantityPort.loadPurchasedQuantity(EVENT_ID, USER_ID))
                .thenReturn(quantity);
    }

    private void givenStock(int available) {
        when(loadStockPort.loadStock(EVENT_ID)).thenReturn(Optional.of(new Stock(EVENT_ID, available, 0)));
    }

    @Test
    @DisplayName("庫存足夠且未超限時扣減庫存並建立訂單")
    void deductsStockAndCreatesOrder() {
        givenEventExists();
        givenAlreadyPurchased(0);
        givenStock(500);
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
    }

    @Test
    @DisplayName("訂單的建立時間取自注入的 Clock,不是系統當下時間")
    void usesInjectedClockForCreatedAt() {
        givenEventExists();
        givenAlreadyPurchased(0);
        givenStock(500);
        when(saveOrderPort.saveOrder(any())).thenAnswer(inv -> inv.getArgument(0));

        service.purchase(command(1));

        ArgumentCaptor<Order> submitted = ArgumentCaptor.forClass(Order.class);
        verify(saveOrderPort).saveOrder(submitted.capture());
        assertThat(submitted.getValue().createdAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("累計超過限購上限時拒絕,且不讀取庫存")
    void rejectsWhenExceedingPurchaseLimit() {
        givenEventExists();
        givenAlreadyPurchased(3);

        assertThatThrownBy(() -> service.purchase(command(2))).isInstanceOf(PurchaseLimitExceededException.class);

        // 限購檢查在庫存之前：被擋下的請求不該碰庫存，也不該進入後續策略的鎖競爭
        verify(loadStockPort, never()).loadStock(any());
        verify(updateStockPort, never()).updateStock(any());
        verify(saveOrderPort, never()).saveOrder(any());
    }

    @Test
    @DisplayName("累計剛好等於上限時通過")
    void allowsWhenExactlyAtLimit() {
        givenEventExists();
        givenAlreadyPurchased(3);
        givenStock(500);
        when(saveOrderPort.saveOrder(any())).thenAnswer(inv -> inv.getArgument(0));

        service.purchase(command(1));

        verify(updateStockPort).updateStock(any());
    }

    @Test
    @DisplayName("同時超限且庫存不足時,回報的是限購 —— 檢查順序的驗證")
    void limitCheckPrecedesStockCheck() {
        givenEventExists();
        givenAlreadyPurchased(4);

        assertThatThrownBy(() -> service.purchase(command(3))).isInstanceOf(PurchaseLimitExceededException.class);

        verify(loadStockPort, never()).loadStock(any());
    }

    @Test
    @DisplayName("庫存不足時拋出例外,且不寫回庫存也不建立訂單")
    void rejectsWhenStockInsufficient() {
        givenEventExists();
        givenAlreadyPurchased(0);
        givenStock(1);

        assertThatThrownBy(() -> service.purchase(command(3))).isInstanceOf(InsufficientStockException.class);

        verify(updateStockPort, never()).updateStock(any());
        verify(saveOrderPort, never()).saveOrder(any());
    }

    @Test
    @DisplayName("場次不存在時拋出例外,且不查詢已購數也不讀取庫存")
    void rejectsWhenEventMissing() {
        when(loadEventPort.loadEvent(EVENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.purchase(command(1))).isInstanceOf(EventNotFoundException.class);

        verify(loadUserPurchasedQuantityPort, never()).loadPurchasedQuantity(any(), any());
        verify(loadStockPort, never()).loadStock(any());
    }

    @Test
    @DisplayName("庫存列缺失時視同場次不存在")
    void rejectsWhenStockRowMissing() {
        givenEventExists();
        givenAlreadyPurchased(0);
        when(loadStockPort.loadStock(EVENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.purchase(command(1))).isInstanceOf(EventNotFoundException.class);

        verify(saveOrderPort, never()).saveOrder(any());
    }
}
