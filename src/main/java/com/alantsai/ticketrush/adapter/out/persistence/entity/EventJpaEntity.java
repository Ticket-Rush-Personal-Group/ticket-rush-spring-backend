package com.alantsai.ticketrush.adapter.out.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 場次的持久化映射。
 *
 * <p>本型別不得外洩至 {@code adapter.out.persistence} 之外(由 ArchUnit 強制)。
 * 領域模型是 {@link com.alantsai.ticketrush.domain.model.Event},兩者以 mapper 銜接。
 *
 * <p>{@code created_at} 由資料庫的 DEFAULT now() 填入,應用不需要讀寫,故不映射。
 */
@Entity
@Table(name = "event")
public class EventJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "sales_start_at", nullable = false)
    private Instant salesStartAt;

    @Column(name = "total_quantity", nullable = false)
    private int totalQuantity;

    /** JPA 要求的無參建構子,不供應用程式使用。 */
    protected EventJpaEntity() {}

    public EventJpaEntity(Long id, String name, Instant salesStartAt, int totalQuantity) {
        this.id = id;
        this.name = name;
        this.salesStartAt = salesStartAt;
        this.totalQuantity = totalQuantity;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Instant getSalesStartAt() {
        return salesStartAt;
    }

    public int getTotalQuantity() {
        return totalQuantity;
    }
}
