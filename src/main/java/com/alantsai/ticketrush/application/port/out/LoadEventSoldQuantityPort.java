package com.alantsai.ticketrush.application.port.out;

import com.alantsai.ticketrush.domain.valueobject.EventId;

/**
 * 某場次已售出的張數總和。**對帳用。**
 *
 * <p>與 {@link LoadUserPurchasedQuantityPort} 的差別在聚合範圍:那個是「某人買了幾張」
 * (限購用),這個是「這場賣了幾張」(對帳用)。兩者的查詢與索引都不同,
 * 硬併成一個帶條件參數的方法只會得到一個誰都看不懂的簽章。
 *
 * <p><b>這是「超賣」的唯一權威來源。</b> 第 3 層把庫存搬到 Redis 之後,
 * 資料庫的 {@code stock.available} 不再被扣減 —— 它保留為**初始配額**。
 * 因此判斷有沒有超賣,比的是這裡的售出張數與初始配額,不是資料庫的庫存餘量。
 */
public interface LoadEventSoldQuantityPort {

    int loadSoldQuantity(EventId eventId);
}
