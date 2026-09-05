/**
 * 入站 web adapter:REST Controller 與 Thymeleaf 管理介面 Controller。
 *
 * <p>不得標註 {@code @Transactional}(會把 HTTP 處理納入交易範圍),
 * 不得標註 {@code @CrossOrigin}(CORS 集中於 infrastructure.config.WebConfig)。
 */
package com.alantsai.ticketrush.adapter.in.web;
