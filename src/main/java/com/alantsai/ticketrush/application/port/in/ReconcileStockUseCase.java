package com.alantsai.ticketrush.application.port.in;

import com.alantsai.ticketrush.domain.valueobject.EventId;
import java.util.List;

/**
 * 對帳:比對快取的扣減量與資料庫的訂單量,必要時把遺失的庫存還回去。
 *
 * <p>即時補償只處理「我知道我失敗了」的情況。崩潰、逾時、消費者被終止 ——
 * 這些沒有人回補,**只有對帳抓得到**。因此兩者都要,不是二選一。
 */
public interface ReconcileStockUseCase {

    /** 對帳單一場次。 */
    ReconciliationResult reconcile(EventId eventId);

    /** 對帳所有開賣中的場次。排程工作呼叫這個。 */
    List<ReconciliationResult> reconcileAll();
}
