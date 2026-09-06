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

    /**
     * 冪等鍵是否已存在。第 3 層的落庫消費者用它判斷「這則訊息先前已成功落庫」。
     *
     * <p>查詢落在 {@code uq_purchase_order_idempotency_key} 這個唯一索引上。
     */
    boolean existsByIdempotencyKey(String idempotencyKey);

    /** 某場次已售出的張數總和。對帳以此與 Redis 的扣減量比對。 */
    @Query("select coalesce(sum(o.quantity), 0) from OrderJpaEntity o where o.eventId = :eventId")
    int sumSoldQuantity(@Param("eventId") Long eventId);
}
