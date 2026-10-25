package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.entity.Voucher;
import com.hmdp.exception.SeckillCampaignPublicationPendingException;
import com.hmdp.service.IVoucherService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 验证发布结果未知时仍返回原券编号，重试只调用初始化业务。 */
class VoucherControllerTest {
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
