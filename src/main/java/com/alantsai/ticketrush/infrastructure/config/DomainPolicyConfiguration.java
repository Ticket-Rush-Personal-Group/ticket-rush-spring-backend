package com.alantsai.ticketrush.infrastructure.config;

import com.alantsai.ticketrush.domain.policy.PurchaseLimitPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 領域政策的實例化。
 *
 * <p>政策類別本身位於 domain 且**不得有任何 Spring 註解**(由 ArchUnit 強制),
 * 因此由 infrastructure 依設定值建立實例 —— 這是「domain 不依賴框架」與
 * 「設定可調整」兩個需求的交會點。
 *
 * <p>上限不寫死在 domain 的理由:壓測時調整它會改變競爭形態。上限越低,
 * 同一人的請求被限購擋下的越多,真正進入鎖競爭的請求就越少,分布因此不同。
 */
@Configuration
public class DomainPolicyConfiguration {

    @Bean
    PurchaseLimitPolicy purchaseLimitPolicy(@Value("${ticket-rush.max-tickets-per-user:4}") int maxTicketsPerUser) {
        return new PurchaseLimitPolicy(maxTicketsPerUser);
    }
}
