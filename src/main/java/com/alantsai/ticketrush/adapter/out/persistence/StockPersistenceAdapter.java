package com.alantsai.ticketrush.adapter.out.persistence;

import com.alantsai.ticketrush.adapter.out.persistence.mapper.JpaEntityMapper;
import com.alantsai.ticketrush.adapter.out.persistence.repository.StockJpaRepository;
import com.alantsai.ticketrush.application.port.out.LoadStockPort;
import com.alantsai.ticketrush.application.port.out.UpdateStockPort;
import com.alantsai.ticketrush.domain.model.Stock;
import com.alantsai.ticketrush.domain.valueobject.EventId;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 庫存的持久化 adapter。
 *
 * <p>目前實作無鎖讀取與絕對值寫回。各策略專屬的讀寫方式(SELECT ... FOR UPDATE、
 * 回傳影響列數的條件式 UPDATE)於各自的 change 加入,屆時是**新增 port 與方法**,
 * 而不是修改既有的 —— 四層策略必須能並存,否則無法在同一個建置中互相比較。
 *
 * <p>此處沒有 {@code @Transactional}:交易邊界屬於 application service,由 ArchUnit 強制。
 */
@Component
public class StockPersistenceAdapter implements LoadStockPort, UpdateStockPort {

    private final StockJpaRepository repository;

    public StockPersistenceAdapter(StockJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<Stock> loadStock(EventId eventId) {
        return repository.findById(eventId.value()).map(JpaEntityMapper::toDomain);
    }

    /**
     * 以絕對值寫回庫存。
     *
     * <p>寫入的是呼叫端算好的值,不是 {@code available = available - ?} 的增量更新。
     * 這是無鎖策略的定義性行為,詳見 {@link UpdateStockPort} 的說明。
     */
    @Override
    public void updateStock(Stock stock) {
        repository.save(JpaEntityMapper.toEntity(stock));
    }
}
