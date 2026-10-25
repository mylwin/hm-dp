import preview from '../mocks/preview-data.json';
import { request } from './session';
import type { DataMode, Id, Voucher } from '../types';

export function createVouchers(mode: DataMode) {
  const isPreview = mode === 'preview';
  return {
    vouchers: (id: Id, signal?: AbortSignal): Promise<Voucher[]> => isPreview
      ? Promise.resolve(preview.vouchers.filter((voucher) => String(voucher.shopId) === String(id)))
      : request(`/voucher/list/${encodeURIComponent(id)}`, { signal }),
  };
}

/** @deprecated 旧 Stream 请求仅保留为历史参考，当前界面使用 submitReliableSeckill。 */
export function seckill(id: Id): Promise<Id> {
  return request(`/voucher-order/seckill/${encodeURIComponent(id)}`, { method: 'POST' });
}

// 当前仅使用新链路；旧页面分支保留作参考，不再由环境变量启用。
export const reliableSeckillEnabled = true;

export type SeckillRequestResult = {
  orderId: string;
  requestId: string;
  voucherId: string;
  status: 'PREPARING' | 'ACCEPTED' | 'SUCCEEDED' | 'REJECTED' | 'CLOSED';
  statusDescription: string;
  reason: string | null;
  reasonDescription: string | null;
};

// 重试传入原 requestId，不能在 API 函数中重新生成。
export function submitReliableSeckill(voucherId: Id, requestId: string, signal?: AbortSignal): Promise<SeckillRequestResult> {
  return request(`/voucher-order/reliable/seckill/${encodeURIComponent(voucherId)}`, {
    method: 'POST', body: JSON.stringify({ requestId }), signal,
  });
}

export function querySeckillRequest(requestId: string, signal?: AbortSignal): Promise<SeckillRequestResult> {
  return request(`/voucher-order/reliable/requests/${encodeURIComponent(requestId)}`, { signal });
}
