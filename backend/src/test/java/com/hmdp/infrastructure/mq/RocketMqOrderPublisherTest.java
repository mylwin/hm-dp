package com.hmdp.infrastructure.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.config.RocketMqProperties;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.remoting.exception.RemotingTimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RocketMqOrderPublisherTest {
    private DefaultMQProducer producer;
    private RocketMqOrderPublisher publisher;
    private final SeckillOrderMessage event = new SeckillOrderMessage(1, "event-1", 100L, 20L, 30L,
            "trace-1", Instant.parse("2026-10-24T00:00:00Z"));

    @BeforeEach
    void setUp() {
        producer = mock(DefaultMQProducer.class);
        publisher = new RocketMqOrderPublisher(producer, new ObjectMapper().findAndRegisterModules(),
                new RocketMqProperties());
    }

    @Test
    void sendsStableBusinessKeysAndReturnsOnlyBrokerReceipt() throws Exception {
        SendResult result = mock(SendResult.class);
        when(result.getSendStatus()).thenReturn(SendStatus.SEND_OK);
        when(result.getMsgId()).thenReturn("broker-message-id");
        when(producer.send(any(Message.class), eq(3000L))).thenReturn(result);

        assertThat(publisher.publish(event)).isEqualTo(
                new RocketMqOrderPublisher.PublishReceipt("event-1", "broker-message-id"));
        publisher.publish(event);

        ArgumentCaptor<Message> messages = ArgumentCaptor.forClass(Message.class);
        verify(producer, times(2)).send(messages.capture(), eq(3000L));
        for (Message message : messages.getAllValues()) {
            assertThat(message.getTopic()).isEqualTo("hmdp-seckill-order-create");
            assertThat(message.getKeys()).contains("event-1", "100");
            assertThat(message.getUserProperty("traceId")).isEqualTo("trace-1");
            assertThat(new String(message.getBody(), StandardCharsets.UTF_8))
                    .contains("\"eventId\":\"event-1\"", "\"orderId\":100");
        }
    }

    @ParameterizedTest
    @EnumSource(value = SendStatus.class, names = "SEND_OK", mode = EnumSource.Mode.EXCLUDE)
    void doesNotTreatUnconfirmedStorageAsSuccessfulPublication(SendStatus status) throws Exception {
        SendResult result = mock(SendResult.class);
        when(result.getSendStatus()).thenReturn(status);
        when(producer.send(any(Message.class), anyLong())).thenReturn(result);
        assertThatThrownBy(() -> publisher.publish(event)).isInstanceOf(OrderMessagePublishException.class);
    }

    @Test
    void propagatesTimeoutForLaterOutboxRetry() throws Exception {
        when(producer.send(any(Message.class), anyLong()))
                .thenThrow(new RemotingTimeoutException("10.211.55.9:10911", 3000));
        assertThatThrownBy(() -> publisher.publish(event))
                .isInstanceOf(OrderMessagePublishException.class).hasCauseInstanceOf(RemotingTimeoutException.class);
        verify(producer, times(1)).send(any(Message.class), anyLong());
    }

    @Test
    void preservesThreadInterruption() throws Exception {
        when(producer.send(any(Message.class), anyLong())).thenThrow(new InterruptedException());
        try {
            assertThatThrownBy(() -> publisher.publish(event)).isInstanceOf(OrderMessagePublishException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void rejectsUnsupportedSchemaBeforePublication() {
        assertThatThrownBy(() -> new SeckillOrderMessage(2, "event-1", 100L, 20L, 30L,
                "trace-1", Instant.now())).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(producer);
    }
}
