package com.alantsai.ticketrush.adapter.out.persistence.repository;

import com.alantsai.ticketrush.adapter.out.persistence.entity.StockJpaEntity;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 庫存的 Spring Data JPA repository。
 *
 * <p>各策略需要的讀寫方式差異極大,因此是**新增方法**而非修改既有的 ——
 * 四層必須能在同一個建置中並存才能互相比較。
 */
public interface StockJpaRepository extends JpaRepository<StockJpaEntity, Long> {

    /**
     * 以排他鎖讀取庫存,對應 `SELECT ... FOR UPDATE`。
     *
     * <p>使用 JPQL 加 {@code @Lock} 而非原生 SQL:Hibernate 會產生對應方言的語法,
     * 且 entity 進入持久化 context —— 後續更新走同一個 session,不需要額外的寫入路徑。
     * 原生 SQL 則要自行處理 entity 狀態,容易產生「鎖了一個物件、更新另一個物件」的錯誤。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from StockJpaEntity s where s.eventId = :eventId")
    Optional<StockJpaEntity> findByIdForUpdate(@Param("eventId") Long eventId);

    /**
     * 條件式 UPDATE:版本相符才寫入,並將版本推進一格。回傳影響列數。
     *
     * <p><b>{@code StockJpaEntity} 刻意不標 {@code @Version},樂觀鎖在此手動實作。</b>
     * 那個註解會讓 Hibernate 對**所有**對該 entity 的更新都套用版本檢查,
     * 而第 0 層(無鎖)與第 1 層(悲觀鎖)走的是同一條 {@code save()} 路徑 ——
     * 標上去等於一併改掉另外兩層的行為,四層就不再是同一個比較基準。
     *
     * <p><b>WHERE 只比對版本,不含 {@code available >= ?}。</b> 加上去會讓回傳 0
     * 同時代表「版本衝突」與「庫存不足」,而這兩者要的處置相反(重試 / 立即拒絕)。
     * 可用量的檢查是領域規則,留在 {@code Stock.deduct}。
     *
     * <p>{@code clearAutomatically}:UPDATE 走的是資料庫,不經過持久化 context ——
     * 不清除的話,同一交易內若有人再讀這一列,拿到的會是快取中**更新前**的 entity,
     * 而不是資料庫的實際值。這種不一致不會報錯,只會讓後續判斷用到舊版本號。
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update StockJpaEntity s
               set s.available = :available, s.version = s.version + 1
             where s.eventId = :eventId and s.version = :version
            """)
    int compareAndDeduct(
            @Param("eventId") Long eventId, @Param("available") int available, @Param("version") long version);
}
