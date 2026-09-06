package com.alantsai.ticketrush.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Redis 相關的 bean 定義。
 *
 * <p>{@code StringRedisTemplate} 與連線工廠由 Spring Boot 自動組態,此處不重複定義 ——
 * 只放自動組態不會提供的東西。
 */
@Configuration
public class RedisConfig {

    /**
     * 預扣腳本。
     *
     * <p><b>以 classpath 資源載入,不寫成 Java 字串。</b> 字串裡的 Lua 沒有任何語法標示與縮排支援,
     * 而且會與 Java 的跳脫規則混在一起 —— 一個少掉的引號會變成執行期才發現的
     * 「腳本語法錯誤」,而錯誤訊息指的是 Redis 收到的那一整串,不是原始碼的哪一行。
     *
     * <p>{@code RedisScript} 會快取腳本的 SHA1 並優先以 {@code EVALSHA} 執行,
     * 因此不會每次請求都把腳本內容送過去。
     */
    @Bean
    RedisScript<Long> preDeductScript() {
        return RedisScript.of(new ClassPathResource("redis/pre-deduct.lua"), Long.class);
    }

    /**
     * 回補腳本:庫存加回、已購數減回,兩者原子完成。
     *
     * <p>分兩次呼叫會有一段兩個 key 不一致的時間,而那段時間該使用者的限購額度被錯誤地佔用著。
     */
    @Bean
    RedisScript<Long> restoreScript() {
        return RedisScript.of(new ClassPathResource("redis/restore.lua"), Long.class);
    }
}
