package com.alantsai.ticketrush;

import com.alantsai.ticketrush.testsupport.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * 應用啟動的煙霧測試:Spring context 能完整載入。
 *
 * <p>引入 Testcontainers 設定後,本測試同時涵蓋「資料來源能建立且連得上」—— 加入 JPA 相依之後,
 * context 載入必然包含 DataSource 的初始化,沒有可用的資料庫就會失敗。
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TicketRushApplicationTests {

    @Test
    void contextLoads() {}
}
