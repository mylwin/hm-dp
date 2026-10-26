package com.hmdp.service;

import com.hmdp.infrastructure.mq.SeckillOrderMessage;

/**
 * 新链路建单协议，不启动旧 Stream 消费线程。
 *
 * @author myl
 */
public interface ISeckillOrderService {
    /**
     * 确保数据库已有一人一单唯一约束。
     *
     * @throws IllegalStateException 唯一索引缺失
     */
    void verifySchema();

    /**
     * 按账本幂等建单，返回前已提交建单或关闭事务。
     *
     * @param message 已解析的建单消息
     * @throws IllegalArgumentException 消息与账本不符
     * @throws IllegalStateException    数据未就绪、外层存在事务或数据不一致
     */
    void createFromMessage(SeckillOrderMessage message);
}
