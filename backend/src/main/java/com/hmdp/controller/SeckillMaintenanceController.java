package com.hmdp.controller;

import com.hmdp.config.SeckillMaintenanceProperties;
import com.hmdp.dto.Result;
import com.hmdp.service.ISeckillMaintenanceService;
import com.hmdp.security.UserContext;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 登录及独立运维令牌双重保护的查询和审计操作入口，默认不注册。
 *
 * @author myl
 */
@RestController
@RequestMapping("/ops/seckill")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "hmdp.seckill", name = {"reliable-components-enabled", "maintenance.operations-enabled"}, havingValue = "true")
public class SeckillMaintenanceController {
    private final ISeckillMaintenanceService maintenance;
    private final SeckillMaintenanceProperties properties;

    /**
     * 应用启动时检查维护参数和独立运维令牌，避免开放没有强令牌保护的运维接口。
     *
     * @throws IllegalArgumentException 参数越界，或运维接口已开启但令牌少于 32 字符
     */
    @PostConstruct
    public void validate() {
        properties.validate();
    }

    /**
     * 验证登录及独立令牌后，查询数据库里的未完成任务、最老等待时间和恢复错误。
     * 只返回数字指标，不暴露消息正文或凭据。
     *
     * @param token 请求头 X-Seckill-Ops-Token，允许为空，必须与配置的独立运维令牌相同
     * @return 权限通过时返回 200 和指标；否则返回 403，不调用维护查询
     */
    @GetMapping("/health")
    public ResponseEntity<Result> health(@RequestHeader(value = "X-Seckill-Ops-Token", required = false) String token) {
        return authorized(token) ? ResponseEntity.ok(Result.ok(maintenance.health())) : forbidden();
    }

    /**
     * 验证运维权限后，列出最早尚未处置的非法消息和死信摘要，供选择排查对象。
     *
     * @param token 请求头中的独立运维令牌，允许为空
     * @return 权限通过时返回 200 和有界摘要列表，不含原文；权限不足返回 403
     */
    @GetMapping("/quarantine")
    public ResponseEntity<Result> quarantine(@RequestHeader(value = "X-Seckill-Ops-Token", required = false) String token) {
        return authorized(token) ? ResponseEntity.ok(Result.ok(maintenance.quarantinedMessages())) : forbidden();
    }

    /**
     * 验证运维权限后，按原订单编号恢复建单消息投递，并从登录上下文记录真实操作者。
     * 不接收替换消息正文；是否能重投由 Service 核对账本、预占、原事件及可选隔离原文。
     *
     * @param orderId 要恢复的正数原订单编号
     * @param token   请求头中的独立运维令牌，允许为空
     * @param command 操作原因及可选隔离编号；原因必须非空白且不超过 256 字符
     * @return Service 已提交重投后返回 200 成功；权限不足为 403；业务异常交现有全局异常处理返回失败
     */
    @PostMapping("/orders/{orderId}/replay")
    public ResponseEntity<Result> replay(@PathVariable Long orderId,
                                         @RequestHeader(value = "X-Seckill-Ops-Token", required = false) String token, @RequestBody Command command) {
        if (!authorized(token)) {
            return forbidden();
        }
        maintenance.replay(orderId, command.quarantineId(), UserContext.getUser().getId().toString(), command.reason());
        return ResponseEntity.ok(Result.ok());
    }

    /**
     * 验证运维权限后，请求关闭尚未建单的抢购，并登记后续释放任务。
     * 响应成功只表示关闭和任务已提交，不代表 Redis 库存已经回补。
     *
     * @param orderId 要关闭的正数原订单编号
     * @param token   请求头中的独立运维令牌，允许为空
     * @param command 关闭依据，原因必须非空白且不超过 256 字符
     * @return 关闭及释放任务提交后返回 200 成功；权限不足为 403；业务不符合条件时由全局异常处理返回失败
     */
    @PostMapping("/orders/{orderId}/close")
    public ResponseEntity<Result> close(@PathVariable Long orderId,
                                        @RequestHeader(value = "X-Seckill-Ops-Token", required = false) String token, @RequestBody Command command) {
        if (!authorized(token)) {
            return forbidden();
        }
        maintenance.close(orderId, UserContext.getUser().getId().toString(), command.reason());
        return ResponseEntity.ok(Result.ok());
    }

    /**
     * 验证运维权限后，把已人工确认无需重投的隔离消息标为已处置并保存操作者。
     * 只关闭隔离待办，不关闭抢购请求，也不改变订单或库存。
     *
     * @param id      要处置的 64 位小写十六进制隔离编号
     * @param token   请求头中的独立运维令牌，允许为空
     * @param command 人工核对依据，必须非空白且不超过 256 字符
     * @return 处置和审计提交后返回 200 成功；权限不足为 403；其他错误交全局异常处理
     */
    @PostMapping("/quarantine/{id}/dismiss")
    public ResponseEntity<Result> dismiss(@PathVariable String id,
                                          @RequestHeader(value = "X-Seckill-Ops-Token", required = false) String token, @RequestBody Command command) {
        if (!authorized(token)) {
            return forbidden();
        }
        maintenance.dismissQuarantine(id, UserContext.getUser().getId().toString(), command.reason());
        return ResponseEntity.ok(Result.ok());
    }

    /**
     * 检查当前请求是否已有登录用户，并使用常量时间字节比较核对独立运维令牌。
     * 调用方不能仅凭普通登录就执行关闭或重投。
     *
     * @param token 请求头中的令牌，允许为空
     * @return 登录用户存在、两边令牌非 null 且内容相同时为 true；否则为 false
     */
    private boolean authorized(String token) {
        return UserContext.getUser() != null && token != null && properties.getOperationsToken() != null
                && MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), properties.getOperationsToken().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 为运维权限不足的请求生成统一失败响应，不泄漏具体资源是否存在。
     *
     * @return HTTP 403，响应 success=false，提示无秒杀运维权限
     */
    private ResponseEntity<Result> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Result.fail("无秒杀运维权限"));
    }

    /**
     * 受控操作参数，不接收用户编号或任意消息正文。
     *
     * @param reason       必填操作原因
     * @param quarantineId 可选隔离编号，仅重投时使用
     */
    public record Command(String reason, String quarantineId) {
    }
}
