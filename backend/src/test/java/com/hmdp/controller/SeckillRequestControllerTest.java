package com.hmdp.controller;

import com.hmdp.constants.SeckillRequestStatus;
import com.hmdp.config.SeckillOrderProperties;
import com.hmdp.dto.SeckillRequestResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.exception.SeckillAcceptancePendingException;
import com.hmdp.security.UserContext;
import com.hmdp.service.ISeckillRequestService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 验证接口用户来源、未知结果和长编号传输，不访问数据库。 */
class SeckillRequestControllerTest {
    private final ISeckillRequestService requests = mock(ISeckillRequestService.class);
    private final SeckillOrderProperties properties = new SeckillOrderProperties();
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new SeckillRequestController(requests, properties)).build();

    /** 清除当前线程身份，避免测试互相污染。 */
    @AfterEach
    void clearUser() { UserContext.removeUser(); }

    /**
     * 无身份时返回 401，不能调用受理服务。
     * @throws Exception HTTP 测试调用失败
     */
    @Test
    void anonymousRequestIsRejected() throws Exception {
        mvc.perform(get("/voucher-order/reliable/orders/1001")).andExpect(status().isUnauthorized());
        verifyNoInteractions(requests);
    }

    /**
     * HTTP 中额外传入用户编号无效，使用鉴权上下文并保留长编号字符串。
     * @throws Exception HTTP 测试调用失败
     */
    @Test
    void usesAuthenticatedUserAndStringOrderId() throws Exception {
        login();
        when(requests.queryByRequest(1L, "request-1")).thenReturn(result(SeckillRequestStatus.ACCEPTED));
        mvc.perform(post("/voucher-order/reliable/seckill/30").contentType("application/json")
                        .content("{\"requestId\":\"request-1\",\"userId\":999}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.orderId").value("856725678012345678"))
                .andExpect(jsonPath("$.data.status").value("ACCEPTED"));
        verify(requests).accept(eq(1L), eq(30L), eq("request-1"), anyString());
    }

    /**
     * 受理超时返回已持久化 PREPARING，不伪造拒绝。
     * @throws Exception HTTP 测试调用失败
     */
    @Test
    void uncertainAcceptanceReturnsPreparing() throws Exception {
        login();
        when(requests.accept(eq(1L), eq(30L), eq("request-1"), anyString()))
                .thenThrow(new SeckillAcceptancePendingException(1001L, new IllegalStateException()));
        when(requests.queryByOrder(1L, 1001L)).thenReturn(result(SeckillRequestStatus.PREPARING));
        mvc.perform(post("/voucher-order/reliable/seckill/30").contentType("application/json")
                        .content("{\"requestId\":\"request-1\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PREPARING"));
    }

    /**
     * 无权限与不存在统一返回 404，查询必须带当前用户编号。
     * @throws Exception HTTP 测试调用失败
     */
    @Test
    void missingAndForeignRequestsLookTheSame() throws Exception {
        login();
        mvc.perform(get("/voucher-order/reliable/orders/1001")).andExpect(status().isNotFound());
        verify(requests).queryByOrder(1L, 1001L);
    }

    /**
     * 无法解析的路径和 JSON 返回参数错误，不落入通用服务器异常。
     * @throws Exception HTTP 测试调用失败
     */
    @Test
    void malformedHttpParametersAreBadRequests() throws Exception {
        login();
        mvc.perform(get("/voucher-order/reliable/orders/not-a-number")).andExpect(status().isBadRequest());
        mvc.perform(post("/voucher-order/reliable/seckill/30").contentType("application/json").content("{"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(requests);
    }

    /**
     * 暂停提交不创建账本，但原请求查询仍然可用，便于维护期间收尾。
     * @throws Exception MVC 请求失败
     */
    @Test
    void pausedAdmissionKeepsQueriesAvailable() throws Exception {
        login();
        properties.setAcceptingRequests(false);
        mvc.perform(post("/voucher-order/reliable/seckill/30").contentType("application/json")
                .content("{\"requestId\":\"request-1\"}"))
                .andExpect(status().isServiceUnavailable());
        verifyNoInteractions(requests);
        when(requests.queryByRequest(1L, "request-1")).thenReturn(result(SeckillRequestStatus.ACCEPTED));
        mvc.perform(get("/voucher-order/reliable/requests/request-1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("ACCEPTED"));
    }

    /** 设置模拟鉴权身份。 */
    private void login() {
        UserDTO user = new UserDTO(); user.setId(1L); UserContext.saveUser(user);
    }

    /**
     * 构造接口展示结果。
     * @param status 请求状态
     * @return 长编号结果样本
     */
    private SeckillRequestResult result(SeckillRequestStatus status) {
        return new SeckillRequestResult("856725678012345678", "request-1", "30", status,
                status.getDescription(), null, null);
    }
}
