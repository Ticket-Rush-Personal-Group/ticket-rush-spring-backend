/**
 * 入站 port:對外提供的 use case 介面。
 *
 * <p>四層併發策略是本 package 內 PurchaseTicketUseCase 的四個實作,而非單一出站 port 的
 * 四個 adapter —— 各策略的流程、依賴的出站 port、交易邊界皆不相同。
 */
package com.alantsai.ticketrush.application.port.in;
