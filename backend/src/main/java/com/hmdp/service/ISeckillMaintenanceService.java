package com.hmdp.service;

import java.util.List;
import java.util.Map;

/**
 * 秒杀订单的后台收尾与恢复接口，负责预占确认、关闭后的回补、卡住请求的恢复及人工处置。
 *
 * @author myl
 */
public interface ISeckillMaintenanceService {
    /**
     * 执行数据库中待处理的预占确认和释放任务。
     * 成功订单的预占改为 CONFIRMED，不再扣库存；已关闭且未建单的请求释放预占，最多回补一份。
     * 每轮最多领取配置的批量数。失败时尝试登记下次执行时间；失去租约后不覆盖他人状态，未确认任务可到期接管。
     *
     * @return 本轮领取的任务数；0 表示未领到任务，计数包含执行失败的任务
     * @throws IllegalStateException 调用方已有事务或入口线程已中断
     */
    int processReservationTasks();

    /**
     * 继续发布尚未开放的新券，并检查长期停在 PREPARING 或 ACCEPTED 的请求。
     * PREPARING 沿用原请求继续受理；ACCEPTED 核对订单及预占后恢复原建单事件的投递。
     * 活动和请求各最多检查一个配置批次，失败后延迟再查，不因为等待过久自动关闭请求。
     *
     * @return 本轮领取恢复资格的活动数与请求数之和；0 表示没有领取，不代表所有待办均已完成
     * @throws IllegalStateException 调用方已有事务或入口线程已中断
     */
    int recoverDue();

    /**
     * 让原订单的建单消息重新进入待投递状态，不创建新订单编号或替换消息正文。
     * 仅处理活动已开放、代次一致且预占有效的 ACCEPTED 请求，并核对原订单和原消息身份。
     * 人工操作不能抢占有效发送租约；SYSTEM 自动恢复只唤醒 SENT，其他状态交原投递器处理。
     * 实际重投会登记审计；关联隔离消息时，其原文也必须与账本一致，处置和重投一起提交。
     *
     * @param orderId 原请求绑定的正数订单编号
     * @param quarantineId 待关联的隔离编号；null 表示不处理隔离记录
     * @param operator 当前登录操作者编号；后台自动恢复使用 SYSTEM，长度 1 到 128 字符
     * @param reason 重投原因，非空白且长度为 1 到 256 字符
     * @throws IllegalArgumentException 订单编号、操作者或原因不符合约定
     * @throws IllegalStateException 已有外层事务、线程中断、状态不允许重投、消息不符或发送租约仍有效
     */
    void replay(Long orderId, String quarantineId, String operator, String reason);

    /**
     * 关闭尚未建单的抢购请求，并同时保存后续归还 Redis 预占的任务。
     * 仅允许活动 READY 且原订单不存在的 PREPARING、ACCEPTED 请求；已有订单或终态时拒绝关闭。
     * 关闭状态、释放任务和审计一起提交，Redis 库存由后台任务随后回补，当前方法不直接加库存。
     *
     * @param orderId 要关闭的原请求订单编号，必须为正数
     * @param operator 当前登录操作者编号，非空白且长度为 1 到 128 字符
     * @param reason 关闭依据，非空白且长度为 1 到 256 字符
     * @throws IllegalArgumentException 订单编号、操作者或原因不符合约定
     * @throws IllegalStateException 已有外层事务、线程中断、活动未开放、代次冲突、已有订单或请求已终态
     */
    void close(Long orderId, String operator, String reason);

    /**
     * 把已人工核对、确定无需重投的隔离消息标为已处置，并保存操作者和处置依据。
     * 保留隔离原文及摘要，不改变抢购请求、订单或库存；记录不存在或已经处置时返回失败。
     *
     * @param quarantineId 隔离记录编号，必须为 64 位小写十六进制字符串
     * @param operator 当前登录操作者编号，非空白且长度为 1 到 128 字符
     * @param reason 无需重投的依据，非空白且长度为 1 到 256 字符
     * @throws IllegalArgumentException 编号、操作者或原因不符合约定
     * @throws IllegalStateException 已有外层事务、线程中断、隔离记录不存在或已经处置
     */
    void dismissQuarantine(String quarantineId, String operator, String reason);

    /**
     * 查询尚未完成的建单、确认、释放、活动发布及请求恢复情况，供排查积压和触发告警。
     * 统计来自数据库，不代表 Broker 的精确队列深度，也不返回完整消息或凭据。
     *
     * @return 指标名到数字的映射，包括待办数量、最老年龄秒数、最大尝试次数和错误数量；没有待办时为 0
     */
    Map<String, Object> health();

    /**
     * 列出最早仍未处置的非法消息和死信，帮助运维定位需要核对的消息。
     * 每次最多返回配置批量数，包含隔离编号、消息编号、摘要和错误类型，不包含原始正文。
     *
     * @return 未处置消息的摘要列表，没有待办时为空列表
     */
    List<Map<String, Object>> quarantinedMessages();
}
