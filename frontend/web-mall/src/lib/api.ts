import { parse } from 'lossless-json';
import preview from '../preview-data.json';
import type { Blog, DataMode, Id, Result, Shop, ShopQuery, ShopType, User, Voucher } from '../types';

export class ApiError extends Error {
  constructor(message: string, public status?: number) { super(message); }
}

const base = (import.meta.env.VITE_API_BASE || '/api').replace(/\/$/, '');
export async function request<T>(path: string, options: RequestInit & { timeoutMs?: number } = {}): Promise<T> {
  const { timeoutMs = 12000, ...fetchOptions } = options;
  const token = sessionStorage.getItem('token');
  const headers = new Headers(options.headers);
  if (options.body && !(options.body instanceof FormData)) headers.set('Content-Type', 'application/json');
  if (token) headers.set('authorization', token);
  const response = await fetch(`${base}${path}`, {
    ...fetchOptions, headers,
    signal: options.signal ? AbortSignal.any([options.signal, AbortSignal.timeout(timeoutMs)]) : AbortSignal.timeout(timeoutMs),
  });
  if (response.status === 401) {
    sessionStorage.removeItem('token');
    window.dispatchEvent(new Event('hmdp:unauthorized'));
    throw new ApiError('登录已过期，请重新登录', 401);
  }
  if (!response.ok) throw new ApiError('暂时无法连接服务，请稍后重试', response.status);
  // Preserve long Snowflake IDs instead of rounding them in JSON.parse.
  const result = parse(await response.text(), undefined, (raw) => {
    const value = Number(raw);
    return Number.isInteger(value) && !Number.isSafeInteger(value) ? raw : value;
  }) as Result<T>;
  if (!result.success) throw new ApiError(result.errorMsg || '操作未完成，请重试');
  return result.data;
}

function queryString(query: object) {
  const result = new URLSearchParams();
  Object.entries(query).forEach(([key, value]) => {
    if (value !== undefined && value !== '') result.set(key, String(value));
  });
  return result.toString();
}

export function createCatalog(mode: DataMode) {
  const isPreview = mode === 'preview';
  return {
    types: (signal?: AbortSignal): Promise<ShopType[]> => isPreview
      ? Promise.resolve([...preview.types].sort((a, b) => a.sort - b.sort))
      : request('/shop-type/list', { signal }),
    shops: (query: ShopQuery, signal?: AbortSignal): Promise<Shop[]> => {
      if (isPreview) {
        const size = query.typeId ? 5 : 10;
        const filtered = preview.shops.filter((shop) =>
          (!query.typeId || String(shop.typeId) === query.typeId) &&
          (!query.name || shop.name.toLowerCase().includes(query.name.toLowerCase())));
        return Promise.resolve(filtered.slice((query.current - 1) * size, query.current * size));
      }
      const path = query.name || !query.typeId ? '/shop/of/name' : '/shop/of/type';
      const params = path.endsWith('name') ? { name: query.name, current: query.current } : query;
      return request(`${path}?${queryString(params)}`, { signal });
    },
    shop: (id: Id, signal?: AbortSignal): Promise<Shop> => {
      if (!isPreview) return request(`/shop/${encodeURIComponent(id)}`, { signal });
      const shop = preview.shops.find((shop) => String(shop.id) === String(id));
      return shop ? Promise.resolve(shop) : Promise.reject(new ApiError('这家商家暂时无法查看', 404));
    },
    vouchers: (id: Id, signal?: AbortSignal): Promise<Voucher[]> => isPreview
      ? Promise.resolve(preview.vouchers.filter((voucher) => String(voucher.shopId) === String(id)))
      : request(`/voucher/list/${encodeURIComponent(id)}`, { signal }),
    blogs: (signal?: AbortSignal, current = 1): Promise<Blog[]> => isPreview
      ? Promise.resolve(preview.blogs.slice((current - 1) * 10, current * 10))
      : request(`/blog/hot?current=${current}`, { signal }),
  };
}

export const auth = {
  me: () => request<User | null>('/user/me'),
  code: (phone: string) => request<void>(`/user/code?phone=${encodeURIComponent(phone)}`, { method: 'POST' }),
  login: (phone: string, code: string) => request<string>('/user/login', {
    method: 'POST', body: JSON.stringify({ phone, code }),
  }),
  logout: () => request<void>('/user/logout', { method: 'POST' }),
};

export function seckill(id: Id): Promise<Id> {
  return request(`/voucher-order/seckill/${encodeURIComponent(id)}`, { method: 'POST' });
}
