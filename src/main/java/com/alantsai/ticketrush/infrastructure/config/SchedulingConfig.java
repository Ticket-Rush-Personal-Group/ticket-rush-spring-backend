package com.alantsai.ticketrush.infrastructure.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 啟用排程。
 *
 * <p>目前唯一的排程工作是第 3 層的週期對帳。獨立成一個組態類別而非標在應用主類別上 ——
 * 主類別上的註解會逐年累積成一長串,而每一個都在影響整個應用的行為卻沒有任何說明的位置。
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {}
