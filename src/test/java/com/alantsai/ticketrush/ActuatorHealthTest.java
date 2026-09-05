package com.alantsai.ticketrush;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 健康檢查端點的煙霧測試。
 *
 * <p>驗證兩件事:應用的 Spring context 能完整載入,且 {@code /actuator/health} 可回應。
 * 第 4 支的 compose healthcheck 會依賴這個端點 —— 容器 running 與「可接受請求」之間存在
 * 實質空窗,健康檢查必須打真正的端點而非檢查行程或埠。
 *
 * <p>以測試驗證而非手動啟動:CLAUDE.md 的 Hard Rules 禁止 AI 自行執行
 * {@code ./mvnw spring-boot:run},端點的可用性因此必須進入 {@code ./mvnw test} 的涵蓋範圍。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ActuatorHealthTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("health 端點回 200 且狀態為 UP")
    void healthEndpointIsUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
