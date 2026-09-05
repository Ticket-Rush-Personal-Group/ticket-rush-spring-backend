package com.alantsai.ticketrush.application.port.in;

import com.alantsai.ticketrush.domain.model.OrderStatus;
import com.alantsai.ticketrush.domain.valueobject.EventId;
import com.alantsai.ticketrush.domain.valueobject.OrderId;
import com.alantsai.ticketrush.domain.valueobject.Quantity;

/**
 * 購票結果。
 *
 * <p><b>不含任何策略識別資訊。</b> 四層策略共用同一契約,呼叫端無從得知也不需得知當前是哪一種 ——
 * 一旦洩漏,呼叫端就可能依它分支,策略便不再可自由抽換。
 */
public record PurchaseResult(OrderId orderId, EventId eventId, Quantity quantity, OrderStatus status) {}
