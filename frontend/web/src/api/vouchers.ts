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

export function seckill(id: Id): Promise<Id> {
  return request(`/voucher-order/seckill/${encodeURIComponent(id)}`, { method: 'POST' });
}
