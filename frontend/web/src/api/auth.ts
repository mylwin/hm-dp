import { send } from './http';
import { login, logout, request } from './session';
import type { User } from '../types';

export { hasLoginSession } from './session';

export const auth = {
  me: () => request<User | null>('/user/me'),
  code: (phone: string) => send<void>(`/user/code?phone=${encodeURIComponent(phone)}`, { method: 'POST' }, null),
  login,
  logout,
};
