import { ApiError, send, type RequestOptions } from './http';

const sessionKey = 'hmdp-session'; // 仅为恢复提示，不保存访问令牌或刷新凭证。
let generation = 0;
let refreshInFlight: Promise<string> | null = null;
let logoutPending = false;

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

export async function login(phone: string, code: string) {
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
}

export async function logout() {
  generation++;
  refreshInFlight = null;
  logoutPending = true;
  try {
    await withSessionLock(() => send<void>('/user/logout', {
      method: 'POST', headers: { 'X-Requested-With': 'XMLHttpRequest' },
    }));
    clearLogin(false);
  } finally { logoutPending = false; }
}
