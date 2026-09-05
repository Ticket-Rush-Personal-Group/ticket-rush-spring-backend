package com.alantsai.ticketrush.application.port.in;

/**
 * 購票 use case。四層併發策略是本介面的四個實作。
 *
 * <p><b>本介面不得被單獨注入</b>(由 ArchUnit 強制)。取用途徑只有
 * {@code PurchaseFacade} 持有的 {@code Map<String, PurchaseTicketUseCase>}。
 * 以 {@code @Qualifier} 指定特定實作同樣禁止 —— 那會讓呼叫端綁定單一策略,
 * 「同一個 API、四種實作」的前提就不成立了。
 */
public interface PurchaseTicketUseCase {

    PurchaseResult purchase(PurchaseTicketCommand command);
}
