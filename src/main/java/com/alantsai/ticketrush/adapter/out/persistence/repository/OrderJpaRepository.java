package com.alantsai.ticketrush.adapter.out.persistence.repository;

import com.alantsai.ticketrush.adapter.out.persistence.entity.OrderJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 訂單的 Spring Data JPA repository。僅供 persistence adapter 內部使用。 */
public interface OrderJpaRepository extends JpaRepository<OrderJpaEntity, Long> {

    /**
     * 累計購買張數。
     *
     * <p>{@code sum(o.quantity)} 而非 {@code count(o)} —— 限購的單位是張數。
     * 無訂單時 {@code sum} 回傳 null,以 {@code coalesce} 轉為 0,呼叫端不必處理 null。
     *
     * <p>查詢落在第 2 支建立的 {@code idx_purchase_order_event_user} 索引上。
     */
    @Query("select coalesce(sum(o.quantity), 0) from OrderJpaEntity o "
            + "where o.eventId = :eventId and o.userId = :userId")
    int sumPurchasedQuantity(@Param("eventId") Long eventId, @Param("userId") Long userId);
}
