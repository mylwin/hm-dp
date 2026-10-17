import snapshot from '../preview-data.json';
import people from '../preview-people.json';
import { ApiError, request } from './api';
import type { Blog, BlogInput, DataMode, FeedCursor, FeedResult, Id, User, UserInfo } from '../types';

export function createCommunity(mode: DataMode) {
  const preview = mode === 'preview';
  const requireLive = () => { if (preview) throw new ApiError('当前为设计预览，请连接后端后再操作'); };
  return {
    blog: (id: Id, signal?: AbortSignal): Promise<Blog> => {
      if (!preview) return request(`/blog/${encodeURIComponent(id)}`, { signal });
      const blog = snapshot.blogs.find((blog) => String(blog.id) === String(id));
      return blog ? Promise.resolve(blog) : Promise.reject(new ApiError('这篇笔记暂时无法查看', 404));
    },
    user: (id: Id, signal?: AbortSignal): Promise<User | null> => preview
      ? Promise.resolve(people.find((user) => String(user.id) === String(id)) || null)
      : request(`/user/${encodeURIComponent(id)}`, { signal }),
    info: (id: Id, signal?: AbortSignal): Promise<UserInfo | null> => preview
      ? Promise.resolve(null)
      : request(`/user/info/${encodeURIComponent(id)}`, { signal }),
    userBlogs: (id: Id, current: number, mine: boolean, signal?: AbortSignal): Promise<Blog[]> => preview
      ? Promise.resolve(snapshot.blogs.filter((blog) => String(blog.userId) === String(id)).slice((current - 1) * 10, current * 10))
      : request(mine ? `/blog/of/me?current=${current}` : `/blog/of/user?id=${encodeURIComponent(id)}&current=${current}`, { signal }),
    feed: (cursor: FeedCursor, signal?: AbortSignal): Promise<FeedResult | null> => preview
      ? Promise.resolve(null)
      : request(`/blog/of/follow?lastId=${cursor.lastId}&offset=${cursor.offset}`, { signal }),
    likes: (id: Id, signal?: AbortSignal): Promise<User[]> => preview ? Promise.resolve([]) : request(`/blog/likes/${encodeURIComponent(id)}`, { signal }),
    isFollowing: (id: Id, signal?: AbortSignal): Promise<boolean> => preview ? Promise.resolve(false) : request(`/follow/or/not/${encodeURIComponent(id)}`, { signal }),
    common: (id: Id, signal?: AbortSignal): Promise<User[]> => preview ? Promise.resolve([]) : request(`/follow/common/${encodeURIComponent(id)}`, { signal }),
    like: (id: Id) => { requireLive(); return request<void>(`/blog/like/${encodeURIComponent(id)}`, { method: 'PUT' }); },
    follow: (id: Id, follow: boolean) => { requireLive(); return request<void>(`/follow/${encodeURIComponent(id)}/${follow}`, { method: 'PUT' }); },
    signCount: (): Promise<number> => preview ? Promise.resolve(0) : request('/user/sign/count'),
    sign: () => { requireLive(); return request<void>('/user/sign', { method: 'PUT' }); },
    publish: (blog: BlogInput) => { requireLive(); return request<Id>('/blog', { method: 'POST', body: JSON.stringify(blog) }); },
    upload: (file: File, signal?: AbortSignal) => {
      requireLive();
      const body = new FormData(); body.append('file', file);
      return request<string>('/upload/blog', { method: 'POST', body, signal, timeoutMs: 60000 });
    },
    deleteUpload: (url: string) => { requireLive(); return request<void>(`/upload/blog/delete?name=${encodeURIComponent(url)}`); },
  };
}
