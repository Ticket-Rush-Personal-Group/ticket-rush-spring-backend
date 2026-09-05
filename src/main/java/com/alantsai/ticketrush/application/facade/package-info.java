/**
 * Facade:組合多個 use case,並封裝策略的選擇。
 *
 * <p>PurchaseFacade 持有 Map&lt;String, PurchaseTicketUseCase&gt;,對外只暴露單一入口。
 * 策略的選擇不得洩漏到 adapter 層。
 */
package com.alantsai.ticketrush.application.facade;
