package com.alantsai.ticketrush.application.port.out;

import com.alantsai.ticketrush.domain.model.Stock;
import com.alantsai.ticketrush.domain.valueobject.EventId;
import java.util.Optional;

/**
 * 讀取庫存,不加任何鎖。
 *
 * <p>這是無鎖對照組(第 3 支)使用的 port。悲觀鎖需要的
 * {@code LoadStockForUpdatePort}(SELECT ... FOR UPDATE)與樂觀鎖需要的
 * {@code CompareAndDeductStockPort}(回傳影響列數)是不同的介面,跟著各自的策略進來 ——
 * 四層策略對外部世界的需求本就不同,強行收斂成單一 port 只會得到一個為了容納全部而失去意義的介面。
 */
public interface LoadStockPort {
    Optional<Stock> loadStock(EventId eventId);
}
