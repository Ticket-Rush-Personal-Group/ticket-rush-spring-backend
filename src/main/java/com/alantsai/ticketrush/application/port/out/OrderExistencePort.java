package com.alantsai.ticketrush.application.port.out;

import com.alantsai.ticketrush.domain.valueobject.IdempotencyKey;

/**
 * 以冪等鍵查詢訂單是否已存在。**Redis 預扣策略(第 3 層)的落庫路徑使用。**
 *
 * <p>訊息被重新領取時(消費者崩潰、逾時、或 {@code XCLAIM}),同一筆預扣會再次落庫。
 * 若沒有這道檢查,第二次落庫會撞上唯一約束 —— 而**把約束違反誤判為「落庫失敗」的代價是
 * 回補一次庫存,也就是超賣**。
 *
 * <p>唯一約束仍然是最終的保證;本 port 只是讓常見情況(訊息重送)不必依賴例外流程。
 * <b>兩者都要:先查是為了不靠例外做流程控制,約束是為了擋住查詢與寫入之間的競態。</b>
 */
public interface OrderExistencePort {

    boolean exists(IdempotencyKey idempotencyKey);
}
