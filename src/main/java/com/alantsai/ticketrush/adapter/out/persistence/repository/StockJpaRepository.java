package com.alantsai.ticketrush.adapter.out.persistence.repository;

import com.alantsai.ticketrush.adapter.out.persistence.entity.StockJpaEntity;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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
}
