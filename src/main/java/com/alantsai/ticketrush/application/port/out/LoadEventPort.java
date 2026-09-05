package com.alantsai.ticketrush.application.port.out;

import com.alantsai.ticketrush.domain.model.Event;
import com.alantsai.ticketrush.domain.valueobject.EventId;
import java.util.Optional;

/** 讀取場次。 */
public interface LoadEventPort {
    Optional<Event> loadEvent(EventId eventId);
}
