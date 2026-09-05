package com.alantsai.ticketrush.application.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.alantsai.ticketrush.application.port.in.PurchaseResult;
import com.alantsai.ticketrush.application.port.in.PurchaseTicketCommand;
import com.alantsai.ticketrush.application.port.in.PurchaseTicketUseCase;
import com.alantsai.ticketrush.domain.model.OrderStatus;
import com.alantsai.ticketrush.domain.valueobject.EventId;
import com.alantsai.ticketrush.domain.valueobject.IdempotencyKey;
import com.alantsai.ticketrush.domain.valueobject.OrderId;
import com.alantsai.ticketrush.domain.valueobject.Quantity;
import com.alantsai.ticketrush.domain.valueobject.UserId;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 策略選擇的單元測試。以手動組裝的 Map 取代 Spring 注入,不啟動 context。 */
@ExtendWith(MockitoExtension.class)
class PurchaseFacadeTest {

    private static final PurchaseTicketCommand COMMAND =
            new PurchaseTicketCommand(new EventId(7L), new UserId(1L), new Quantity(1), new IdempotencyKey("key-001"));

    private static final PurchaseResult RESULT =
            new PurchaseResult(new OrderId(42L), new EventId(7L), new Quantity(1), OrderStatus.PENDING);

    @Mock
    private PurchaseTicketUseCase noLockStrategy;

    @Mock
    private PurchaseTicketUseCase pessimisticStrategy;

    private PurchaseFacade facadeWith(String currentStrategy) {
        Map<String, PurchaseTicketUseCase> strategies =
                Map.of("noLock", noLockStrategy, "pessimistic", pessimisticStrategy);
        return new PurchaseFacade(strategies, new StrategyRegistry(currentStrategy));
    }

    @Test
    @DisplayName("依 registry 的當前值選到對應的策略實作")
    void delegatesToCurrentStrategy() {
        when(noLockStrategy.purchase(COMMAND)).thenReturn(RESULT);

        PurchaseResult result = facadeWith("noLock").purchase(COMMAND);

        assertThat(result).isEqualTo(RESULT);
        verify(noLockStrategy).purchase(COMMAND);
        verify(pessimisticStrategy, never()).purchase(any());
    }

    @Test
    @DisplayName("切換策略後改由另一個實作處理")
    void switchingStrategyChangesDelegate() {
        when(pessimisticStrategy.purchase(COMMAND)).thenReturn(RESULT);

        PurchaseFacade facade = facadeWith("pessimistic");
        facade.purchase(COMMAND);

        verify(pessimisticStrategy).purchase(COMMAND);
        verify(noLockStrategy, never()).purchase(any());
    }

    @Test
    @DisplayName("策略名稱不存在時拋出含可用清單的例外,而非 NullPointerException")
    void unknownStrategyThrowsWithAvailableNames() {
        assertThatThrownBy(() -> facadeWith("noSuchStrategy").purchase(COMMAND))
                .isInstanceOf(UnknownStrategyException.class)
                .hasMessageContaining("noSuchStrategy")
                .hasMessageContaining("noLock")
                .hasMessageContaining("pessimistic");
    }

    @Test
    @DisplayName("StrategyRegistry 的 switchTo 會改變後續選擇")
    void registrySwitchTakesEffect() {
        when(pessimisticStrategy.purchase(COMMAND)).thenReturn(RESULT);
        StrategyRegistry registry = new StrategyRegistry("noLock");
        PurchaseFacade facade =
                new PurchaseFacade(Map.of("noLock", noLockStrategy, "pessimistic", pessimisticStrategy), registry);

        registry.switchTo("pessimistic");
        facade.purchase(COMMAND);

        verify(pessimisticStrategy).purchase(COMMAND);
        verify(noLockStrategy, never()).purchase(any());
    }
}
