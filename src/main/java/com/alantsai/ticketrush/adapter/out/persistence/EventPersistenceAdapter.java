package com.alantsai.ticketrush.adapter.out.persistence;

import com.alantsai.ticketrush.adapter.out.persistence.mapper.JpaEntityMapper;
import com.alantsai.ticketrush.adapter.out.persistence.repository.EventJpaRepository;
import com.alantsai.ticketrush.application.port.out.LoadEventPort;
import com.alantsai.ticketrush.domain.model.Event;
import com.alantsai.ticketrush.domain.valueobject.EventId;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** 場次的持久化 adapter。 */
@Component
public class EventPersistenceAdapter implements LoadEventPort {

    private final EventJpaRepository repository;

    public EventPersistenceAdapter(EventJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<Event> loadEvent(EventId eventId) {
        return repository.findById(eventId.value()).map(JpaEntityMapper::toDomain);
    }
}
