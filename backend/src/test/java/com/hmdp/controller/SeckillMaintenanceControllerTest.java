package com.hmdp.controller;

import com.hmdp.config.SeckillMaintenanceProperties;
import com.hmdp.dto.UserDTO;
import com.hmdp.security.UserContext;
import com.hmdp.service.ISeckillMaintenanceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 使用 MVC 参数绑定验证运维令牌、可信操作者及禁止任意 payload 重投的接口契约。 */
class SeckillMaintenanceControllerTest {
    private final String token = "test-operations-token-at-least-32-characters";
    private final ISeckillMaintenanceService service = mock(ISeckillMaintenanceService.class);

    /** 清除本测试设置的可信用户，避免泄漏到其他用例。 */
    @AfterEach
    void clearUser() {
        UserContext.removeUser();
    }

    /**
     * 未登录、无令牌和错误令牌均无法读取运维数据。
     * @throws Exception MVC 请求失败
     */
    @Test
    void rejectsMissingIdentityAndToken() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(controller()).build();
        mvc.perform(get("/ops/seckill/health").header("X-Seckill-Ops-Token", token)).andExpect(status().isForbidden());
        login();
        mvc.perform(get("/ops/seckill/health")).andExpect(status().isForbidden());
        mvc.perform(get("/ops/seckill/health").header("X-Seckill-Ops-Token", "wrong")).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    /**
     * 合法登录和令牌才能读取健康信息，响应没有凭据。
     * @throws Exception MVC 请求失败
     */
    @Test
    void authorizedHealthReturnsMetrics() throws Exception {
        login();
        when(service.health()).thenReturn(Map.of("pending_tasks", 2));
        MockMvcBuilders.standaloneSetup(controller()).build()
                .perform(get("/ops/seckill/health").header("X-Seckill-Ops-Token", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.pending_tasks").value(2))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(token))));
    }

    /**
     * 重投与关闭使用登录上下文中的操作者，正文不能覆盖身份或订单消息。
     * @throws Exception MVC 请求失败
     */
    @Test
    void writesUseTrustedOperatorAndOriginalOrder() throws Exception {
        login();
        var mvc = MockMvcBuilders.standaloneSetup(controller()).build();
        mvc.perform(post("/ops/seckill/orders/1001/replay").header("X-Seckill-Ops-Token", token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"核对后重投\",\"quarantineId\":\"isolated\",\"operator\":\"forged\",\"payload\":\"forged\"}"))
                .andExpect(status().isOk());
        verify(service).replay(1001L, "isolated", "7", "核对后重投");
        mvc.perform(post("/ops/seckill/orders/1001/close").header("X-Seckill-Ops-Token", token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"核对后关闭\"}"))
                .andExpect(status().isOk());
        verify(service).close(1001L, "7", "核对后关闭");
    }

    /** 开启运维但没有强令牌时配置校验失败。 */
    @Test
    void operationsRequireIndependentStrongToken() {
        SeckillMaintenanceProperties properties = new SeckillMaintenanceProperties();
        properties.setOperationsEnabled(true);
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalArgumentException.class);
        properties.setOperationsToken("short");
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalArgumentException.class);
        properties.setOperationsToken(token);
        assertThatCode(properties::validate).doesNotThrowAnyException();
    }

    /**
     * 创建已开启令牌校验的控制器。
     * @return 测试控制器
     */
    private SeckillMaintenanceController controller() {
        SeckillMaintenanceProperties properties = new SeckillMaintenanceProperties();
        properties.setOperationsEnabled(true); properties.setOperationsToken(token);
        return new SeckillMaintenanceController(service, properties);
    }

    /** 设置由鉴权层提供的测试用户。 */
    private void login() {
        UserDTO user = new UserDTO(); user.setId(7L); UserContext.saveUser(user);
    }
}
