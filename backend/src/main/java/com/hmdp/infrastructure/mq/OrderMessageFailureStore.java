package com.hmdp.infrastructure.mq;

import com.hmdp.entity.QuarantinedOrderMessage;
import com.hmdp.mapper.SeckillMaintenanceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 消费确认前持久化非法和死信消息，消息原文有界且不输出到日志。
 *
 * @author myl
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "hmdp.seckill", name = "reliable-components-enabled", havingValue = "true")
public class OrderMessageFailureStore {
    private final SeckillMaintenanceMapper maintenance;
    private final PlatformTransactionManager transactionManager;

    /**
     * 把非法消息或死信保存到隔离表，数据库提交成功后才允许调用方确认消费。
     * 按来源、Topic、物理消息编号和正文摘要生成固定隔离编号，重复到达保留旧记录及处置结果。
     * 正文最多保存 64 KiB，超大正文只留摘要；日志只输出隔离编号和错误类型，不输出正文。
     * 数据库异常继续向上传递，调用方不能在保存未确认时向 Broker 返回消费成功。
     *
     * @param message   当前 Broker 消息，包含原物理消息编号、Topic、正文及重试次数
     * @param source    INVALID 表示非法协议，DLQ 表示当前订单组死信
     * @param errorType 需要排查的稳定错误类型，不传完整异常正文或凭据
     * @throws IllegalStateException 当前已有外层事务、线程已中断或摘要算法不可用
     */
    public void record(MessageExt message, String source, String errorType) {
        if (TransactionSynchronizationManager.isActualTransactionActive() || Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException("隔离记录必须在未中断的独立调用中提交");
        }
        byte[] body = message.getBody() == null ? new byte[0] : message.getBody();
        String bodyHash = digest(body);
        QuarantinedOrderMessage record = new QuarantinedOrderMessage();
        record.setQuarantineId(digest((source + "|" + message.getTopic() + "|" + message.getMsgId() + "|" + bodyHash).getBytes(StandardCharsets.UTF_8)));
        record.setSource(source);
        record.setMessageId(message.getMsgId());
        record.setTopic(message.getTopic());
        record.setBody(body.length <= 65536 ? body : null);
        record.setBodyHash(bodyHash);
        record.setErrorType(errorType);
        record.setReconsumeTimes(message.getReconsumeTimes());
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout(5);
        transaction.executeWithoutResult(status -> maintenance.quarantine(record));
        log.error("SECKILL_MESSAGE_QUARANTINED source={} id={} msgId={} errorType={}", source,
                record.getQuarantineId(), message.getMsgId(), errorType);
    }

    /**
     * 计算正文或隔离身份字段的 SHA-256 摘要，用于识别重复消息而不把原文放进编号。
     *
     * @param value 要计算摘要的非 null 字节数组
     * @return 64 位小写十六进制摘要，相同输入得到相同结果
     * @throws IllegalStateException 当前 Java 环境不提供 SHA-256 算法
     */
    private String digest(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("运行环境不支持 SHA-256", ex);
        }
    }
}
