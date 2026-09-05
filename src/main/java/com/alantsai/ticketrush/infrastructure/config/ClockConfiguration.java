package com.alantsai.ticketrush.infrastructure.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 時間來源。
 *
 * <p>注入 {@link Clock} 而非直接呼叫 {@code Instant.now()},讓時間在測試中可控 ——
 * 訂單的建立時間與 Phase 2 的逾時判斷都需要可預測的時鐘。
 */
@Configuration
public class ClockConfiguration {

    @Bean
    Clock systemClock() {
        return Clock.systemUTC();
    }
}
