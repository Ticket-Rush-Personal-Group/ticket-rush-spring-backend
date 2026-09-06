package com.alantsai.ticketrush.adapter.out.persistence;

import com.alantsai.ticketrush.adapter.out.persistence.mapper.JpaEntityMapper;
import com.alantsai.ticketrush.adapter.out.persistence.repository.StockJpaRepository;
import com.alantsai.ticketrush.application.port.out.CompareAndDeductStockPort;
import com.alantsai.ticketrush.application.port.out.LoadStockForUpdatePort;
import com.alantsai.ticketrush.application.port.out.LoadStockPort;
import com.alantsai.ticketrush.application.port.out.UpdateStockPort;
import com.alantsai.ticketrush.domain.model.Stock;
import com.alantsai.ticketrush.domain.valueobject.EventId;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 庫存的持久化 adapter,實作各策略所需的不同讀寫方式。
 *
 * <p>此處沒有 {@code @Transactional}:交易邊界屬於 application service,由 ArchUnit 強制。
 * 這對悲觀鎖尤其關鍵 —— 鎖在交易結束時釋放,若交易邊界在 adapter,鎖的範圍就會小於
 * 呼叫端的業務流程,等同沒鎖。
 */
@Component
public class StockPersistenceAdapter
        implements LoadStockPort, LoadStockForUpdatePort, UpdateStockPort, CompareAndDeductStockPort {

    private final StockJpaRepository repository;

    public StockPersistenceAdapter(StockJpaRepository repository) {
        this.repository = repository;
    }

    /** 不加鎖讀取。第 0 層無鎖策略使用。 */
    @Override
    public Optional<Stock> loadStock(EventId eventId) {
        return repository.findById(eventId.value()).map(JpaEntityMapper::toDomain);
    }

    /** 以排他鎖讀取。第 1 層悲觀鎖策略使用。 */
    @Override
    public Optional<Stock> loadStockForUpdate(EventId eventId) {
        return repository.findByIdForUpdate(eventId.value()).map(JpaEntityMapper::toDomain);
    }

    /**
     * 以絕對值寫回庫存。
     *
     * <p>寫入的是呼叫端算好的值,不是增量更新。這是無鎖策略的定義性行為;
     * 悲觀鎖策略同樣使用它,但因為有鎖保護,不會發生 lost update。
     */
    @Override
    public void updateStock(Stock stock) {
        repository.save(JpaEntityMapper.toEntity(stock));
    }

    /**
     * 以版本比對寫回庫存。第 2 層樂觀鎖策略使用。
     *
     * <p>傳入的 {@link Stock} 帶著**新的可用量**與**讀取當時的版本** ——
     * {@code Stock.deduct} 不變動版本正是為了讓後者能完整傳到這裡。
     *
     * @return {@code 1} 代表 CAS 成功,{@code 0} 代表版本已被他人推進
     */
    @Override
    public int compareAndDeduct(Stock deducted) {
        return repository.compareAndDeduct(deducted.eventId().value(), deducted.available(), deducted.version());
    }
}
