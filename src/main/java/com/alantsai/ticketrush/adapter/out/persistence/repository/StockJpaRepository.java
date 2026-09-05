package com.alantsai.ticketrush.adapter.out.persistence.repository;

import com.alantsai.ticketrush.adapter.out.persistence.entity.StockJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 庫存的 Spring Data JPA repository。
 *
 * <p>本支只提供基本讀寫。各併發策略所需的查詢方式差異極大(悲觀鎖需要 SELECT ... FOR UPDATE、
 * 樂觀鎖需要回傳影響列數的條件式 UPDATE),那些跟著各自的策略進來,不在此預先定義。
 */
public interface StockJpaRepository extends JpaRepository<StockJpaEntity, Long> {}
