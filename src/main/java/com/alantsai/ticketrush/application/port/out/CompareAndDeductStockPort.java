package com.alantsai.ticketrush.application.port.out;

import com.alantsai.ticketrush.domain.model.Stock;

/**
 * 以**版本比對**寫回庫存(compare-and-set)。**樂觀鎖策略(第 2 層)專用。**
 *
 * <p>條件為「版本仍是我讀到的那個」。條件成立才寫入並將版本推進一格,不成立則不寫入 ——
 * 這正是樂觀鎖的定義:不阻擋競爭,而是**偵測**它。
 *
 * <p><b>參數是已於領域層扣減完成的 {@link Stock}</b>,它同時帶著新的可用量與**讀取當時的版本**。
 * {@code Stock.deduct} 刻意不變動版本,版本的遞增由持久化層負責 —— 這個分工讓
 * 「我看到的版本」得以完整傳遞到寫入的那一刻,而那正是 CAS 唯一需要的東西。
 *
 * <p><b>條件只包含版本,不包含可用量。</b> 若把 {@code available >= ?} 也放進條件,
 * 回傳 0 就會同時代表「版本衝突」與「庫存不足」,而兩者的正確處置相反:前者應重試,
 * 後者應立即拒絕。無法區分就無法決定,而選錯的代價很具體 —— 對庫存不足重試,
 * 會讓售罄後的請求各自重試到上限才放棄,把重試風暴放大一個數量級。
 * 可用量的檢查留在 {@code Stock.deduct}。
 *
 * @see LoadStockForUpdatePort 悲觀鎖的對應做法:先鎖住,再讓後來者排隊
 */
public interface CompareAndDeductStockPort {

    /**
     * 在版本未被他人推進的前提下寫回庫存。
     *
     * @param deducted 已於領域層扣減完成的庫存,其版本為讀取當時的版本
     * @return 影響列數:{@code 1} 代表 CAS 成功,{@code 0} 代表版本已被他人推進
     */
    int compareAndDeduct(Stock deducted);
}
