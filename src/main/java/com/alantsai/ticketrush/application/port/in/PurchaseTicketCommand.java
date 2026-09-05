package com.alantsai.ticketrush.application.port.in;

import com.alantsai.ticketrush.domain.valueobject.EventId;
import com.alantsai.ticketrush.domain.valueobject.IdempotencyKey;
import com.alantsai.ticketrush.domain.valueobject.Quantity;
import com.alantsai.ticketrush.domain.valueobject.UserId;

/**
 * 購票命令。
 *
 * <p>欄位皆為 value object,因此一個能被建構出來的 command 必然是格式合法的 ——
 * application service 不需要再檢查張數是否為正、冪等鍵是否為空。
 */
public record PurchaseTicketCommand(EventId eventId, UserId userId, Quantity quantity, IdempotencyKey idempotencyKey) {}
