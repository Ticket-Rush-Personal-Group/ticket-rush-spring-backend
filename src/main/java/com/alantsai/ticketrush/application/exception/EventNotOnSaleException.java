package com.alantsai.ticketrush.application.exception;

import com.alantsai.ticketrush.domain.valueobject.EventId;

/**
 * 場次存在,但尚未載入快取庫存 —— **尚未開賣**。
 *
 * <p><b>這既不是「場次不存在」,也不是「賣完了」。</b> 三者的處置完全不同:
 *
 * <ul>
 *   <li>場次不存在 → 使用者該檢查連結
 *   <li><b>尚未開賣 → 使用者該稍後再來</b>
 *   <li>賣完了 → 使用者該放棄或換場次
 * </ul>
 *
 * <p>說成「找不到」會讓使用者以為連結壞了;說成「賣完了」會讓他放棄一場根本還沒開始賣的活動。
 *
 * <p><b>放在 application 而非 domain</b>:快取是否已載入是第 3 層的機制,不是領域概念 ——
 * 前三層根本沒有這個狀態。放進 domain 會讓領域層知道當前用的是哪一種策略。
 *
 * <p>它同時是 Lua 腳本「缺 key 必須保守失敗」那條規則在應用層的對應:
 * **把「不知道」當成「可以」是超賣最廉價的來源。**
 */
public class EventNotOnSaleException extends RuntimeException {

    private final transient EventId eventId;

    public EventNotOnSaleException(EventId eventId) {
        super("場次 %d 尚未開賣:快取中沒有該場次的庫存".formatted(eventId.value()));
        this.eventId = eventId;
    }

    public EventId eventId() {
        return eventId;
    }
}
