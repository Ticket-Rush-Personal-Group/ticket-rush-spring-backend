package com.alantsai.ticketrush.testsupport;

import com.redis.testcontainers.RedisContainer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 整合測試共用的 Testcontainers 設定。
 *
 * <p>所有整合測試 {@code @Import} 本類別,藉由 Spring 的 context 快取共用同一個容器實例 ——
 * PostgreSQL 容器啟動約 1–3 秒,若每個測試類別各起一個,後續大量的併發整合測試會反覆支付這個成本。
 *
 * <p><b>版本寫死 {@code postgres:17}</b>,與 {@code ~/dev-databases} 一致。版本不一致會產生
 * 「開發正常、測試失敗」這類最難定位的問題,而鎖的行為正是版本間可能有差異的部分。
 *
 * <p>{@code @ServiceConnection} 會以容器的實際連線資訊覆蓋 {@code application.yml} 的 datasource,
 * 因此整合測試不會連到共用的 {@code ~/dev-databases} —— 那是刻意的:共用資料庫的
 * {@code max_connections} 是預設 100,併發測試會把它耗盡並波及其他專案。
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    // 以 static 欄位持有容器,而非在 @Bean 方法內 new。
    //
    // Spring 的 test context 快取是按「設定」分組的:有 @AutoConfigureMockMvc 與沒有的測試類別
    // 屬於不同 context。若容器在 @Bean 方法內建立,每個 context 都會各起一個 ——
    // 實測確實啟動了兩次(TicketRushApplicationTests 與 ActuatorHealthTest 各一)。
    // static 欄位讓所有 context 共用同一個實例,容器在整個測試 run 中只啟動一次。
    //
    // Testcontainers 2.x 的 org.testcontainers.postgresql.PostgreSQLContainer 不是泛型類別
    // (1.x 在 org.testcontainers.containers 底下是自遞迴泛型 PostgreSQLContainer<SELF>)。
    // 寫 <> 會編譯失敗:「非泛型類別不能使用 '<>'」。
    // max_connections 提高至 500：Spring 的 context 快取會保留多個 context，
    // 每個 context 各有一個連線池（上限 50）。症狀是「某些測試偶發連不上資料庫」
    // （FATAL: sorry, too many clients already）——而它看起來像不穩定的測試，不像設定問題。
    //
    // **第 8 支再次撞到同一堵牆**：新增三個測試 context 之後 300 也不夠了。
    // 光是拉高上限治標不治本，真正的原因見下方的 minimum-idle 設定。
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:17").withCommand("postgres", "-c", "max_connections=500");

    // Redis 容器同樣以 static 欄位持有，理由與上面完全相同。
    //
    // 模組來自第三方 com.redis（Testcontainers 官方 BOM 沒有 redis 模組），
    // 但版本由 Spring Boot 的 BOM 管理。@ServiceConnection 的支援由
    // spring-boot-data-redis 的 RedisContainerConnectionDetailsFactory 提供，
    // 隨 spring-boot-starter-data-redis 一併進來，不需額外相依。
    //
    // 版本寫死 redis:7，與 ~/dev-databases 及壓測環境一致——
    // Lua 腳本的執行語意與 Stream 的 API 都可能隨版本變動。
    private static final RedisContainer REDIS = new RedisContainer("redis:7");

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return POSTGRES;
    }

    @Bean
    @ServiceConnection
    RedisContainer redisContainer() {
        return REDIS;
    }

    /**
     * 測試環境把 HikariCP 的 {@code minimum-idle} 調小。
     *
     * <p><b>問題的真正原因:{@code minimumIdle} 預設等於 {@code maximumPoolSize}。</b>
     * 正式設定的上限是 50,於是**每一個 Spring test context 一啟動就預先開滿 50 條連線** ——
     * 即使那個 context 只跑了一個不碰資料庫的測試。context 快取又會把它們全部留著,
     * 測試類別一多就必然撞上 {@code max_connections}。
     *
     * <p>調小之後,連線改為按需開啟:真正需要併發的測試仍可長到 50,
     * 其餘 context 只留 2 條。**上限維持 50 不動** —— 那是壓測的測量條件,
     * 為了讓測試跑得過而改掉它,等於讓測試環境與被量測的環境不一致。
     *
     * <p>以 {@link DynamicPropertyRegistrar} 註冊而非放進 {@code src/test/resources/application.yml}:
     * 後者會**整份遮蔽**主設定檔,而不是覆寫其中一個值 —— 那是個安靜且難查的陷阱。
     */
    @Bean
    DynamicPropertyRegistrar testPoolSizing() {
        return registry -> registry.add("spring.datasource.hikari.minimum-idle", () -> 2);
    }
}
