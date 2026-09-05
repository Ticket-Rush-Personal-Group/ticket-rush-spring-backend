package com.alantsai.ticketrush.adapter.out.persistence.repository;

import com.alantsai.ticketrush.adapter.out.persistence.entity.EventJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/** 場次的 Spring Data JPA repository。僅供 persistence adapter 內部使用。 */
public interface EventJpaRepository extends JpaRepository<EventJpaEntity, Long> {}
