import { parse } from 'lossless-json';
import preview from '../preview-data.json';
import type { Blog, DataMode, Id, Result, Shop, ShopQuery, ShopType, User, Voucher } from '../types';

export class ApiError extends Error {
  constructor(message: string, public status?: number) { super(message); }
}

const base = (import.meta.env.VITE_API_BASE || '/api').replace(/\/$/, '');
const sessionKey = 'hmdp-session'; // 仅为恢复提示，不保存访问令牌或刷新凭证。
let generation = 0;
let refreshInFlight: Promise<string> | null = null;
let logoutPending = false;
type RequestOptions = RequestInit & { timeoutMs?: number };

function sessionHint() {
  try { return !!localStorage.getItem(sessionKey); } catch { return false; }
}
export function hasLoginSession() {
  return !!sessionStorage.getItem('token') || sessionHint();
}
function clearLogin(notify: boolean) {
  generation++;
  refreshInFlight = null;
  sessionStorage.removeItem('token');
  try { localStorage.removeItem(sessionKey); } catch { /* Cookie 仍由服务端验证。 */ }
  if (notify) window.dispatchEvent(new Event('hmdp:unauthorized'));
}

// 同一浏览器多个标签页共用 Cookie，串行轮换以避免同时消费旧刷新凭证。
async function withSessionLock<T>(action: () => Promise<T>): Promise<T> {
  return navigator.locks ? navigator.locks.request('hmdp-auth-session', action) : action();
}

async function send<T>(path: string, options: RequestOptions = {}, token = sessionStorage.getItem('token')): Promise<T> {
  const { timeoutMs = 12000, ...fetchOptions } = options;
  const headers = new Headers(options.headers);
  if (options.body && !(options.body instanceof FormData)) headers.set('Content-Type', 'application/json');
  if (token) headers.set('authorization', token);
  const response = await fetch(`${base}${path}`, {
    ...fetchOptions, headers, credentials: 'include',
    signal: options.signal ? AbortSignal.any([options.signal, AbortSignal.timeout(timeoutMs)]) : AbortSignal.timeout(timeoutMs),
  });
  if (response.status === 401) throw new ApiError('登录已过期，请重新登录', 401);
  if (!response.ok) throw new ApiError('暂时无法连接服务，请稍后重试', response.status);
  // Preserve long Snowflake IDs instead of rounding them in JSON.parse.
  const result = parse(await response.text(), undefined, (raw) => {
    const value = Number(raw);
    return Number.isInteger(value) && !Number.isSafeInteger(value) ? raw : value;
  }) as Result<T>;
  if (!result.success) throw new ApiError(result.errorMsg || '操作未完成，请重试');
  return result.data;
}

function expiresSoon(token: string) {
  try {
    const payload = JSON.parse(atob(token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')));
    // exp 只用于安排刷新，身份是否可信仍由服务端验签。
    return typeof payload.exp === 'number' && payload.exp * 1000 <= Date.now() + 60_000;
  } catch { return false; }
}

async function refreshAccessToken(): Promise<string> {
  if (refreshInFlight) return refreshInFlight;
  const started = generation;
  const pending: Promise<string> = withSessionLock(async () => {
    if (logoutPending || started !== generation) throw new ApiError('登录状态已变化，请重试', 409);
    const token = await send<string>('/user/refresh', {
      method: 'POST', headers: { 'X-Requested-With': 'XMLHttpRequest' },
    }, null);
    if (logoutPending || started !== generation) throw new ApiError('登录状态已变化，请重试', 409);
    if (typeof token !== 'string' || !token) throw new ApiError('登录恢复响应无效', 502);
    sessionStorage.setItem('token', token);
    return token;
  }).catch((error) => {
    // 网络故障不删除七天会话；仅服务端确认失效时要求重新登录。
    if (error instanceof ApiError && error.status === 401 && started === generation) clearLogin(true);
    throw error;
  }).finally(() => { if (refreshInFlight === pending) refreshInFlight = null; });
  refreshInFlight = pending;
  return pending;
}

export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const started = generation;
  const canRefresh = sessionHint() && !logoutPending;
  let token = sessionStorage.getItem('token');
  if (canRefresh && (!token || expiresSoon(token))) {
    await refreshAccessToken();
    token = sessionStorage.getItem('token');
  }
  try {
    const result = await send<T>(path, options, token);
    if (started !== generation) throw new ApiError('登录状态已变化，请重试', 409);
    return result;
  }
  catch (error) {
    if (!(error instanceof ApiError) || error.status !== 401 || started !== generation) throw error;
    if (canRefresh) {
      // 同批请求可能已拿到新 JWT，优先复用；每个业务请求最多重试一次。
      if (token === sessionStorage.getItem('token')) await refreshAccessToken();
      try {
        const result = await send<T>(path, options);
        if (started !== generation) throw new ApiError('登录状态已变化，请重试', 409);
        return result;
      }
      catch (retryError) {
        if (retryError instanceof ApiError && retryError.status === 401 && started === generation) clearLogin(true);
        throw retryError;
      }
    }
    clearLogin(true);
    throw error;
  }
}

window.addEventListener('storage', (event) => {
  if (event.key !== sessionKey) return;
  generation++;
  refreshInFlight = null;
  sessionStorage.removeItem('token');
  window.dispatchEvent(new Event(event.newValue ? 'hmdp:session-changed' : 'hmdp:signed-out'));
});

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
  code: (phone: string) => send<void>(`/user/code?phone=${encodeURIComponent(phone)}`, { method: 'POST' }, null),
  login: async (phone: string, code: string) => {
    const started = ++generation;
    refreshInFlight = null;
    return withSessionLock(async () => {
      const token = await send<string>('/user/login', { method: 'POST', body: JSON.stringify({ phone, code }) }, null);
      if (started !== generation) throw new ApiError('登录状态已变化，请重试', 409);
      if (typeof token !== 'string' || !token) throw new ApiError('登录响应无效', 502);
      sessionStorage.setItem('token', token);
      try { localStorage.setItem(sessionKey, crypto.randomUUID()); } catch { /* 访问令牌仍可用于本标签页。 */ }
      return token;
    });
  },
  logout: async () => {
    generation++;
    refreshInFlight = null;
    logoutPending = true;
    try {
      await withSessionLock(() => send<void>('/user/logout', {
        method: 'POST', headers: { 'X-Requested-With': 'XMLHttpRequest' },
      }));
      clearLogin(false);
    } finally { logoutPending = false; }
  },
};

export function seckill(id: Id): Promise<Id> {
  return request(`/voucher-order/seckill/${encodeURIComponent(id)}`, { method: 'POST' });
}
