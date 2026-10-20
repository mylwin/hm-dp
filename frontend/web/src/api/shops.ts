import preview from '../mocks/preview-data.json';
import { request } from './session';
import { ApiError } from './http';
import type { DataMode, Id, Shop, ShopQuery, ShopType } from '../types';

function queryString(query: object) {
  const result = new URLSearchParams();
  Object.entries(query).forEach(([key, value]) => {
    if (value !== undefined && value !== '') result.set(key, String(value));
  });
  return result.toString();
}

export function createShops(mode: DataMode) {
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
  };
}
