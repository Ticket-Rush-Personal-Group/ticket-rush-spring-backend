package com.alantsai.ticketrush.adapter.out.persistence.repository;

import com.alantsai.ticketrush.adapter.out.persistence.entity.OrderJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/** 訂單的 Spring Data JPA repository。僅供 persistence adapter 內部使用。 */
public interface OrderJpaRepository extends JpaRepository<OrderJpaEntity, Long> {}
