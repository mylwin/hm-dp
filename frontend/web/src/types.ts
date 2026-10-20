export type Id = number | string;
export type DataMode = 'live' | 'preview';
export interface ShopType { id: Id; name: string; icon: string; sort: number }
export interface Shop {
  id: Id; name: string; typeId: Id; images: string; area: string; address: string;
  x?: number; y?: number; avgPrice?: number; sold?: number; comments?: number;
  score?: number; openHours?: string; distance?: number;
}
export interface Blog {
  id: Id; shopId: Id; userId: Id; title: string; images: string;
  content: string; liked: number; comments?: number; name?: string; icon?: string;
  isLike?: boolean; createTime?: string;
}
export interface Voucher {
  id: Id; shopId: Id; title: string; subTitle: string; rules: string;
  payValue: number; actualValue: number; type: number; status: number;
  stock?: number; beginTime?: string; endTime?: string;
}
export interface User { id: Id; nickName: string; icon?: string }
export interface UserInfo {
  userId: Id; city?: string; introduce?: string; fans?: number; followee?: number;
  birthday?: string; credits?: number; level?: number | boolean;
}
export interface FeedCursor { lastId: number; offset: number }
export interface FeedResult { list: Blog[]; minTime: number; offset: number }
export interface BlogInput { title: string; content: string; images: string; shopId: Id }
export interface Result<T> { success: boolean; errorMsg?: string; data: T; total?: number }
export interface ShopQuery { typeId?: string; name?: string; current: number; x?: number; y?: number }
