/**
 * 出站 port:應用層對外部世界的需求宣告。
 *
 * <p>各併發策略依賴的 port 不同:悲觀鎖需要 LoadStockForUpdatePort,樂觀鎖需要
 * CompareAndDeductStockPort,Redis 預扣需要 StockCachePort 而完全不碰 DB 庫存。
 */
package com.alantsai.ticketrush.application.port.out;
