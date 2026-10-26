import { ApiError, type RequestOptions } from './http';
import { request } from './session';

export type HealthMetric =
  | 'pending_tasks' | 'pending_create_events' | 'pending_confirmations' | 'pending_releases'
  | 'expired_leases' | 'max_attempts' | 'oldest_task_seconds' | 'unfinished_requests'
  | 'preparing_requests' | 'accepted_requests' | 'oldest_request_seconds' | 'quarantine_pending'
  | 'pending_campaigns' | 'oldest_campaign_seconds' | 'blocked_campaigns' | 'recovery_errors';
export type SeckillHealth = Partial<Record<HealthMetric, number | string>>;
export type QuarantinedMessage = {
  quarantine_id: string;
  source: string;
  message_id: string;
  topic: string;
  body_hash: string;
  error_type: string;
  reconsume_times: number;
  created_at: string;
};
export type MaintenanceAction =
  | { kind: 'replay'; orderId: string; quarantineId?: string }
  | { kind: 'close'; orderId: string }
  | { kind: 'dismiss'; quarantineId: string };

async function maintenanceRequest<T>(path: string, opsToken: string, options: RequestOptions = {}): Promise<T> {
  try {
    return await request<T>(`/ops/seckill${path}`, {
      ...options, headers: { 'X-Seckill-Ops-Token': opsToken },
    });
  } catch (error) {
    if (error instanceof ApiError) {
      if (error.status === 403) throw new ApiError('无秒杀运维权限，请检查登录状态和独立运维令牌。', 403);
      if (error.status === 404) throw new ApiError('未找到运维接口，请检查后端 SECKILL_OPERATIONS_ENABLED 开关和代理目标。', 404);
      if (error.status && error.status >= 500) throw new ApiError('后端暂时不可用，请检查服务、数据库迁移和依赖状态。', error.status);
      throw error;
    }
    throw new Error('网络中断或请求超时，结果尚未确认。请先刷新核对，不要连续提交处置。');
  }
}

export function querySeckillHealth(opsToken: string, signal?: AbortSignal): Promise<SeckillHealth> {
  return maintenanceRequest('/health', opsToken, { signal });
}

export function queryQuarantinedMessages(opsToken: string, signal?: AbortSignal): Promise<QuarantinedMessage[]> {
  return maintenanceRequest('/quarantine', opsToken, { signal });
}

export function submitMaintenanceAction(action: MaintenanceAction, reason: string, opsToken: string): Promise<void> {
  // 订单号始终作为字符串传递，不能转为 Number 后拼接 URL。
  const path = action.kind === 'dismiss'
    ? `/quarantine/${encodeURIComponent(action.quarantineId)}/dismiss`
    : `/orders/${encodeURIComponent(action.orderId)}/${action.kind}`;
  const body = action.kind === 'replay' && action.quarantineId
    ? { reason, quarantineId: action.quarantineId }
    : { reason };
  return maintenanceRequest(path, opsToken, { method: 'POST', body: JSON.stringify(body) });
}
