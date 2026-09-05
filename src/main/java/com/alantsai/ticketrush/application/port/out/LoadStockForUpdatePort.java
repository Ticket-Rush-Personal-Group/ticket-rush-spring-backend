package com.alantsai.ticketrush.application.port.out;

import com.alantsai.ticketrush.domain.model.Stock;
import com.alantsai.ticketrush.domain.valueobject.EventId;
import java.util.Optional;

/**
 * 以**排他鎖**讀取庫存(`SELECT ... FOR UPDATE`)。**悲觀鎖策略(第 1 層)專用。**
 *
 * <p>鎖在交易結束時才釋放,因此呼叫端必須位於交易之內 —— 否則鎖立即釋放,等同沒鎖。
 *
 * <p>這是獨立的 port 而非修改 {@link LoadStockPort}:各策略對外部世界的需求不同,
 * 第 0 層需要的正是「不加鎖」的讀取。強行收斂成單一 port 只會得到一個為了容納全部
 * 而失去意義的介面,而且會讓四層無法在同一個建置中並存。
 *
 * <p><b>鎖住庫存列會順帶序列化該場次的整個購票流程</b>,包含限購檢查 ——
 * 前提是那些檢查位於取得鎖之後。
 */
public interface LoadStockForUpdatePort {

    Optional<Stock> loadStockForUpdate(EventId eventId);
}
