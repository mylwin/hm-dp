package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.config.SeckillOrderProperties;
import com.hmdp.dto.SeckillRequestResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.exception.SeckillAcceptancePendingException;
import com.hmdp.security.UserContext;
import com.hmdp.service.ISeckillRequestService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.UUID;

/**
 * 新链路唯一抢购及结果查询入口；暂停接单时保留原请求的查询能力。
 *
 * @author myl
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/voucher-order/reliable")
@ConditionalOnProperty(prefix = "hmdp.seckill", name = {"reliable-components-enabled", "reliable-api-enabled"},
        havingValue = "true")
public class SeckillRequestController {
    private final ISeckillRequestService requests;
    private final SeckillOrderProperties seckillOrderProperties;

    /**
     * 受理当前用户的抢购，响应丢失时沿用请求编号重试。
     *
     * @param voucherId 券编号
     * @param body      客户端生成并保存的请求编号
     * @return 已持久化状态；身份失效为 401，参数非法为 400，暂停接单为 503 且不创建新账本
     */
    @PostMapping("/seckill/{voucherId}")
    public ResponseEntity<Result> accept(@PathVariable Long voucherId, @RequestBody SubmitRequest body) {
        UserDTO user = UserContext.getUser();
        if (user == null) {
            return ResponseEntity.status(401).body(Result.fail("请先登录"));
        }
        if (!seckillOrderProperties.isAcceptingRequests()) {
            return ResponseEntity.status(503).body(Result.fail("秒杀入口暂时关闭，请保留原请求编号并稍后查询"));
        }
        try {
            requests.accept(user.getId(), voucherId, body.requestId(), UUID.randomUUID().toString());
        } catch (SeckillAcceptancePendingException pending) {
            // 请求已持久化，查询最新状态，不能把结果未确认直接当作失败。
            return found(requests.queryByOrder(user.getId(), pending.getOrderId()));
        } catch (IllegalArgumentException invalid) {
            // 参数非法，返回参数错误。
            return ResponseEntity.badRequest().body(Result.fail(invalid.getMessage()));
        }
        // 返回最新持久化状态，后台可能已经完成建单。
        return found(requests.queryByRequest(user.getId(), body.requestId()));
    }

    /**
     * 查询当前用户的订单请求，不区分不存在和无权访问。
     *
     * @param orderId 订单编号
     * @return 抢购结果，无匹配请求时为 404
     */
    @GetMapping("/orders/{orderId}")
    public ResponseEntity<Result> queryOrder(@PathVariable Long orderId) {
        UserDTO user = UserContext.getUser();
        if (user == null) {
            return ResponseEntity.status(401).body(Result.fail("请先登录"));
        }
        try {
            return found(requests.queryByOrder(user.getId(), orderId));
        } catch (IllegalArgumentException invalid) {
            return ResponseEntity.badRequest().body(Result.fail(invalid.getMessage()));
        }
    }

    /**
     * 仅凭原请求编号找回当前用户的抢购进度。
     *
     * @param requestId 客户端请求编号
     * @return 抢购结果，无记录时为 404，不能据此认定迟到请求不会执行
     */
    @GetMapping("/requests/{requestId}")
    public ResponseEntity<Result> queryRequest(@PathVariable String requestId) {
        UserDTO user = UserContext.getUser();
        if (user == null) {
            return ResponseEntity.status(401).body(Result.fail("请先登录"));
        }
        try {
            return found(requests.queryByRequest(user.getId(), requestId));
        } catch (IllegalArgumentException invalid) {
            return ResponseEntity.badRequest().body(Result.fail(invalid.getMessage()));
        }
    }

    /**
     * 统一返回查询结果，不泄露其他用户的请求是否存在。
     *
     * @param result 当前用户可见的结果，允许为 null
     * @return 有结果为 200，无结果为 404
     */
    private ResponseEntity<Result> found(SeckillRequestResult result) {
        return result == null ? ResponseEntity.status(404).body(Result.fail("请求不存在或无权访问"))
                : ResponseEntity.ok(Result.ok(result));
    }

    /**
     * 把当前接口的 JSON 或路径格式错误统一返回为参数错误。
     *
     * @param invalid 请求解析异常
     * @return 400 状态及中文提示
     */
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Result> invalidRequest(Exception invalid) {
        return ResponseEntity.badRequest().body(Result.fail("请求参数格式不正确"));
    }

    /**
     * 抢购提交参数，用户编号从鉴权上下文读取。
     *
     * @param requestId 同一次抢购固定使用的请求编号
     */
    public record SubmitRequest(String requestId) {
    }
}
