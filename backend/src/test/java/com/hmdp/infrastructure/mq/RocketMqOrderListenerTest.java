package com.hmdp.infrastructure.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.service.ISeckillOrderService;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 检查消费确认和失败重试，不连接 Broker。 */
class RocketMqOrderListenerTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final ISeckillOrderService orders = mock(ISeckillOrderService.class);
    private final RocketMqOrderListener listener = new RocketMqOrderListener(json, orders);

    /**
     * 仅在业务方法返回后确认，业务提交异常要求重试。
     * @throws Exception 测试消息序列化失败
     */
    @Test
    void acknowledgesOnlyAfterBusinessReturns() throws Exception {
        MessageExt message = message();
        assertThat(listener.consumeMessage(List.of(message), null)).isEqualTo(ConsumeConcurrentlyStatus.CONSUME_SUCCESS);
        doThrow(new IllegalStateException("提交结果未知")).when(orders).createFromMessage(any());
        assertThat(listener.consumeMessage(List.of(message), null)).isEqualTo(ConsumeConcurrentlyStatus.RECONSUME_LATER);
    }

    /** 非法消息不得静默确认或进入建单服务。 */
    @Test
    void malformedMessageIsRetried() {
        MessageExt invalid = new MessageExt(); invalid.setTags("CREATE_ORDER"); invalid.setBody("{}".getBytes());
        assertThat(listener.consumeMessage(List.of(invalid), null)).isEqualTo(ConsumeConcurrentlyStatus.RECONSUME_LATER);
        verifyNoInteractions(orders);
    }

    /**
     * 已中断线程不再处理订单，并保留中断标记。
     * @throws Exception 测试消息序列化失败
     */
    @Test
    void interruptDefersConsumption() throws Exception {
        MessageExt message = message();
        Thread.currentThread().interrupt();
        try {
            assertThat(listener.consumeMessage(List.of(message), null)).isEqualTo(ConsumeConcurrentlyStatus.RECONSUME_LATER);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verifyNoInteractions(orders);
        } finally { Thread.interrupted(); }
    }

    /**
     * 构造版本合法的建单消息。
     * @return MQ 消息样本
     * @throws Exception JSON 序列化失败
     */
    private MessageExt message() throws Exception {
        MessageExt message = new MessageExt(); message.setTags("CREATE_ORDER");
        message.setBody(json.writeValueAsBytes(new SeckillOrderMessage(1, "order-1", 1L, 2L, 3L,
                "trace", Instant.parse("2026-10-24T00:00:00Z"))));
        return message;
    }
}
