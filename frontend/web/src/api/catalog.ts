import type { DataMode } from '../types';
import { createShops } from './shops';
import { createVouchers } from './vouchers';
import { createBlogs } from './blogs';

export function createCatalog(mode: DataMode) {
  return { ...createShops(mode), ...createVouchers(mode), ...createBlogs(mode) };
}
