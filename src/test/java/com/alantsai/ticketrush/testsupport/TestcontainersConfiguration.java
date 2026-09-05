package com.alantsai.ticketrush.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
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
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return POSTGRES;
    }
}
