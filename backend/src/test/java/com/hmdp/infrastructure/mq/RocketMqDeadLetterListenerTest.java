package com.hmdp.infrastructure.mq;

import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 死信只有隔离事务完成才能确认，持久化失败不得丢弃。 */
class RocketMqDeadLetterListenerTest {
    /** 隔离失败要求 Broker 重试，成功时才确认。 */
    @Test
    void acknowledgesOnlyAfterDurableIsolation() {
        OrderMessageFailureStore store = mock(OrderMessageFailureStore.class);
        RocketMqDeadLetterListener listener = new RocketMqDeadLetterListener(store);
        MessageExt message = new MessageExt();
        assertThat(listener.consumeMessage(List.of(message), null)).isEqualTo(ConsumeConcurrentlyStatus.CONSUME_SUCCESS);
        verify(store).record(message, "DLQ", "BROKER_RETRY_EXHAUSTED");
        doThrow(new IllegalStateException("db unavailable")).when(store).record(any(), anyString(), anyString());
        assertThat(listener.consumeMessage(List.of(message), null)).isEqualTo(ConsumeConcurrentlyStatus.RECONSUME_LATER);
    }
}
