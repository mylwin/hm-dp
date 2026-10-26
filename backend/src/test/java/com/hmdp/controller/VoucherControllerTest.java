package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.entity.Voucher;
import com.hmdp.dto.UserDTO;
import com.hmdp.security.UserContext;
import com.hmdp.exception.SeckillCampaignPublicationPendingException;
import com.hmdp.service.IVoucherService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 验证发布结果未知时仍返回原券编号，重试只调用初始化业务。 */
class VoucherControllerTest {
    /** 清除测试登录上下文，避免其他用例误用身份。 */
    @AfterEach
    void clearUser() {
        UserContext.removeUser();
    }

    /**
     * 普通购买没有登录时拒绝写单，正文传入用户编号也不能绕过鉴权。
     * @throws Exception MVC 请求执行失败
     */
    @Test
    void ordinaryPurchaseRequiresLogin() throws Exception {
        IVoucherService service = mock(IVoucherService.class);
        VoucherController controller = new VoucherController();
        ReflectionTestUtils.setField(controller, "voucherService", service);
        MockMvcBuilders.standaloneSetup(controller).build()
                .perform(post("/voucher/1/purchase").contentType("application/json").content("{\"userId\":99}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    /**
     * 普通券只使用登录用户，长订单号按字符串返回，非法编号按服务契约返回 400。
     * @throws Exception MVC 请求执行失败
     */
    @Test
    void ordinaryPurchaseUsesAuthenticatedUserAndStringOrderId() throws Exception {
        IVoucherService service = mock(IVoucherService.class);
        VoucherController controller = new VoucherController();
        ReflectionTestUtils.setField(controller, "voucherService", service);
        UserDTO currentUser = new UserDTO(); currentUser.setId(7L); UserContext.saveUser(currentUser);
        when(service.purchaseVoucher(7L, 1L)).thenReturn(Result.ok("856725678012345678"));
        when(service.purchaseVoucher(7L, 0L)).thenThrow(new IllegalArgumentException("用户和券编号必须为正数"));
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(post("/voucher/1/purchase").contentType("application/json").content("{\"userId\":99}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").value("856725678012345678"));
        verify(service).purchaseVoucher(7L, 1L);
        mvc.perform(post("/voucher/0/purchase")).andExpect(status().isBadRequest());
    }

    /** 发布待确认时保留实际券编号，响应明确标记为失败。 */
    @Test
    void pendingPublicationReturnsOriginalId() {
        IVoucherService service = mock(IVoucherService.class);
        VoucherController controller = new VoucherController();
        ReflectionTestUtils.setField(controller, "voucherService", service);
        Voucher voucher = new Voucher();
        doThrow(new SeckillCampaignPublicationPendingException(30L, new IllegalStateException("timeout")))
                .when(service).addSeckillVoucher(voucher);
        Result result = controller.addSeckillVoucher(voucher);
        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getData()).isEqualTo("30");
        assertThat(result.getErrorMsg()).contains("原券编号");
    }

    /** 重试入口只核对原活动，不再次调用建券。 */
    @Test
    void retryUsesOriginalVoucher() {
        IVoucherService service = mock(IVoucherService.class);
        VoucherController controller = new VoucherController();
        ReflectionTestUtils.setField(controller, "voucherService", service);
        assertThat(controller.initializeSeckillCampaign(30L).getSuccess()).isTrue();
        verify(service).initializeSeckillCampaign(30L);
        verifyNoMoreInteractions(service);
    }
}
