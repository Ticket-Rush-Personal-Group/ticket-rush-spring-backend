package com.alantsai.ticketrush.application.port.out;

import com.alantsai.ticketrush.domain.model.Stock;

/**
 * 以絕對值寫回庫存。**無鎖策略(第 0 層)專用。**
 *
 * <p>接收的是已於領域層算好的 {@link Stock},而非扣減的增量。這是刻意的設計:
 * 「讀取 → 計算 → 寫回絕對值」正是 lost update 的必要條件,也是第 0 層要示範的問題本身。
 *
 * <p><b>不得改為由資料庫端計算</b>({@code UPDATE stock SET available = available - ?}):
 * 那個寫法在單一 SQL 內完成讀改寫,PostgreSQL 的列鎖會使它天然序列化,根本不會超賣 ——
 * 對照組就失去意義了。第 1、2 層需要的是不同的 port,不是修改這一個。
 */
public interface UpdateStockPort {

    void updateStock(Stock stock);
}
