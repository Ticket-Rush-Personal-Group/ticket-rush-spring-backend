package com.alantsai.ticketrush.domain.exception;

import com.alantsai.ticketrush.domain.valueobject.EventId;

/** 場次不存在。庫存列缺失時同樣拋出本例外 —— 對呼叫端而言兩者都是「這個場次無法購票」。 */
public class EventNotFoundException extends RuntimeException {

    private final transient EventId eventId;

    public EventNotFoundException(EventId eventId) {
        super("場次不存在:" + eventId.value());
        this.eventId = eventId;
    }

    public EventId eventId() {
        return eventId;
    }
}
