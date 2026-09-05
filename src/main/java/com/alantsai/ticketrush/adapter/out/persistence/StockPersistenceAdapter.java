package com.alantsai.ticketrush.adapter.out.persistence;

import com.alantsai.ticketrush.adapter.out.persistence.mapper.JpaEntityMapper;
import com.alantsai.ticketrush.adapter.out.persistence.repository.StockJpaRepository;
import com.alantsai.ticketrush.application.port.out.LoadStockPort;
import com.alantsai.ticketrush.domain.model.Stock;
import com.alantsai.ticketrush.domain.valueobject.EventId;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 庫存的持久化 adapter。
 *
 * <p>本支只實作無鎖讀取。各策略專屬的讀寫方式(FOR UPDATE、條件式 UPDATE)於各自的 change 加入。
 */
@Component
public class StockPersistenceAdapter implements LoadStockPort {

    private final StockJpaRepository repository;

    public StockPersistenceAdapter(StockJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<Stock> loadStock(EventId eventId) {
        return repository.findById(eventId.value()).map(JpaEntityMapper::toDomain);
    }
}
