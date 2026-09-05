package com.alantsai.ticketrush.adapter.out.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 庫存的持久化映射。四層併發策略競爭的對象。
 *
 * <p><b>{@code version} 刻意不標註 {@code @Version}。</b> 標註後 Hibernate 會對每一次 update
 * 都執行樂觀鎖檢查,那會改變第 0 層(無鎖)與第 1 層(悲觀鎖)的行為 ——
 * 四層策略的比較就不再是同一個基準。樂觀鎖於第 7 支啟用時再決定是交給 Hibernate
 * 還是以條件式 UPDATE 自行控制。此處僅作為普通欄位映射。
 */
@Entity
@Table(name = "stock")
public class StockJpaEntity {

    @Id
    @Column(name = "event_id")
    private Long eventId;

    @Column(nullable = false)
    private int available;

    @Column(nullable = false)
    private long version;

    protected StockJpaEntity() {}

    public StockJpaEntity(Long eventId, int available, long version) {
        this.eventId = eventId;
        this.available = available;
        this.version = version;
    }

    public Long getEventId() {
        return eventId;
    }

    public int getAvailable() {
        return available;
    }

    public long getVersion() {
        return version;
    }
}
