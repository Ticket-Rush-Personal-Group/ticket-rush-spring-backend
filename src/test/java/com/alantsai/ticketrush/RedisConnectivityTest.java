package com.alantsai.ticketrush;

import static org.assertj.core.api.Assertions.assertThat;

import com.alantsai.ticketrush.testsupport.TestcontainersConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis 連線的基礎驗證:容器起得來、`@ServiceConnection` 確實覆蓋了設定檔。
 *
 * <p><b>第二個斷言是重點。</b> 若 {@code @ServiceConnection} 沒有生效,測試會連到
 * {@code application.yml} 指定的 {@code localhost:6379} —— 也就是共用的 {@code ~/dev-databases}。
 * 那在開發者本機**依然會全綠**(因為那台 Redis 真的在跑),但實際上:
 *
 * <ul>
 *   <li>違反「併發測試不得跑在共用資料庫」的守則
 *   <li>測試之間會互相污染,而症狀是偶發失敗
 *   <li>CI 上沒有那台 Redis,會變成「本機過、CI 掛」
 * </ul>
 *
 * <p>容器映射的是隨機的宿主埠,因此「埠不等於 6379」就是覆蓋確實生效的證據。
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RedisConnectivityTest {

    private static final int SHARED_DEV_REDIS_PORT = 6379;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private RedisConnectionFactory connectionFactory;

    @Test
    @DisplayName("Redis 可讀寫")
    void canReadAndWrite() {
        redis.opsForValue().set("connectivity:probe", "ok");

        assertThat(redis.opsForValue().get("connectivity:probe")).isEqualTo("ok");

        redis.delete("connectivity:probe");
    }

    @Test
    @DisplayName("連的是 Testcontainers 的容器,不是共用的 ~/dev-databases")
    void connectsToContainerNotSharedDevRedis() {
        assertThat(connectionFactory).isInstanceOf(LettuceConnectionFactory.class);
        LettuceConnectionFactory lettuce = (LettuceConnectionFactory) connectionFactory;

        // 容器映射到隨機宿主埠；仍是 6379 代表 @ServiceConnection 沒生效，
        // 測試正連著共用的 dev redis——而那個狀態下測試依然會全綠。
        assertThat(lettuce.getPort())
                .as("埠仍為 %d 代表連到共用的 ~/dev-databases,而非測試容器", SHARED_DEV_REDIS_PORT)
                .isNotEqualTo(SHARED_DEV_REDIS_PORT);
    }
}
