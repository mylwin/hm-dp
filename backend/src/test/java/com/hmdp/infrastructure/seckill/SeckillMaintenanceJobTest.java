package com.hmdp.infrastructure.seckill;

import com.hmdp.config.SeckillMaintenanceProperties;
import com.hmdp.service.ISeckillMaintenanceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 验证告警输出及单轮故障不会取消后续调度。 */
@ExtendWith(OutputCaptureExtension.class)
class SeckillMaintenanceJobTest {
    /**
     * 长期积压及隔离消息产生结构化日志告警。
     * @param output 捕获的日志输出
     */
    @Test
    void alertsOnBacklogAndQuarantine(CapturedOutput output) {
        ISeckillMaintenanceService service = mock(ISeckillMaintenanceService.class);
        when(service.health()).thenReturn(Map.of("oldest_request_seconds", 180, "quarantine_pending", 1));
        new SeckillMaintenanceJob(service, new SeckillMaintenanceProperties()).monitor();
        assertThat(output).contains("SECKILL_BACKLOG_ALERT").contains("quarantine_pending");
    }

    /**
     * 数据库不可用时本轮失败被记录，下一轮仍能调用维护服务。
     * @param output 捕获的日志输出
     */
    @Test
    void failedRoundDoesNotDisableScheduling(CapturedOutput output) {
        ISeckillMaintenanceService service = mock(ISeckillMaintenanceService.class);
        doThrow(new IllegalStateException("db down")).doReturn(0).when(service).processReservationTasks();
        SeckillMaintenanceJob job = new SeckillMaintenanceJob(service, new SeckillMaintenanceProperties());
        assertThatCode(job::finishReservations).doesNotThrowAnyException();
        assertThatCode(job::finishReservations).doesNotThrowAnyException();
        verify(service, times(2)).processReservationTasks();
        assertThat(output).contains("SECKILL_MAINTENANCE_FAILURE");
    }
}
