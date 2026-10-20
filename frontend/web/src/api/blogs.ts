import preview from '../mocks/preview-data.json';
import { request } from './session';
import type { Blog, DataMode } from '../types';

export function createBlogs(mode: DataMode) {
  const isPreview = mode === 'preview';
  return {
    blogs: (signal?: AbortSignal, current = 1): Promise<Blog[]> => isPreview
      ? Promise.resolve(preview.blogs.slice((current - 1) * 10, current * 10))
      : request(`/blog/hot?current=${current}`, { signal }),
  };
}
