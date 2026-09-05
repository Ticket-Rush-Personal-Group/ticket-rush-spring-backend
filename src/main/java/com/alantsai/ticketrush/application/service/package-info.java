/**
 * 應用服務:use case 的實作,負責編排流程與持有交易邊界。
 *
 * <p>{@code @Transactional} 只屬於本層。第 0/1/2 層需要交易;第 3 層(Redis 預扣)不需要,
 * 因為 Redis 不參與 DB 交易,訂單為非同步落庫。
 */
package com.alantsai.ticketrush.application.service;
