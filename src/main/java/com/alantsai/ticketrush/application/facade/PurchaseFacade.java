package com.alantsai.ticketrush.application.facade;

import com.alantsai.ticketrush.application.port.in.PurchaseResult;
import com.alantsai.ticketrush.application.port.in.PurchaseTicketCommand;
import com.alantsai.ticketrush.application.port.in.PurchaseTicketUseCase;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 購票的唯一入口,並封裝併發策略的選擇。
 *
 * <p>Spring 注入 {@code Map<String, PurchaseTicketUseCase>} 時會自動填入「bean name → 實例」,
 * 因此新增策略只需要新增一個標註 {@code @Service("名稱")} 的實作,不必修改本類別。
 *
 * <p><b>策略的選擇不得洩漏至 adapter 層。</b> controller 只依賴本 facade,對於當前是哪一種策略
 * 一無所知 —— 這是「同一個 API、四種實作」得以成立的前提。
 *
 * <p>相對應地,{@code PurchaseTicketUseCase} 不得被單獨注入(由 ArchUnit 強制),
 * 包含以 {@code @Qualifier} 指定特定實作。
 */
@Component
public class PurchaseFacade {

    private final Map<String, PurchaseTicketUseCase> strategies;
    private final StrategyRegistry registry;

    public PurchaseFacade(Map<String, PurchaseTicketUseCase> strategies, StrategyRegistry registry) {
        this.strategies = strategies;
        this.registry = registry;
    }

    public PurchaseResult purchase(PurchaseTicketCommand command) {
        String strategyName = registry.current();
        PurchaseTicketUseCase strategy = strategies.get(strategyName);
        if (strategy == null) {
            throw new UnknownStrategyException(strategyName, strategies.keySet());
        }
        return strategy.purchase(command);
    }
}
