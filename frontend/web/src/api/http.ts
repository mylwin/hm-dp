import { parse } from 'lossless-json';
import type { Result } from '../types';

export class ApiError extends Error {
  constructor(message: string, public status?: number) { super(message); }
}

const base = (import.meta.env.VITE_API_BASE || '/api').replace(/\/$/, '');
export type RequestOptions = RequestInit & { timeoutMs?: number };

export async function send<T>(path: string, options: RequestOptions = {}, token = sessionStorage.getItem('token')): Promise<T> {
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
