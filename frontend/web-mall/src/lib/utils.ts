export const imagesOf = (images?: string) => images?.split(',').map((src) => src.trim()).filter(Boolean) || [];
export const money = (value: number) => (value / 100).toLocaleString('zh-CN', { minimumFractionDigits: 0, maximumFractionDigits: 2 });
export const errorMessage = (error: unknown) => error instanceof Error
  ? error.message === 'Failed to fetch' ? '暂时无法连接服务，请检查后端是否已启动' : error.message
  : '操作未完成，请稍后重试';
export const distanceText = (distance: number) => distance < 1000 ? `${Math.round(distance)}m` : `${(distance / 1000).toFixed(1)}km`;
export const readableText = (value: string) => {
  const text = value.replace(/<br\s*\/?\s*>|<\/p>/gi, '\n').replace(/\\r\\n|\\n/g, '\n');
  return new DOMParser().parseFromString(text, 'text/html').body.textContent || '';
};
export const textToHtml = (value: string) => value.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;').replace(/\n/g, '<br>');
export const dateLabel = (value?: string) => {
  if (!value) return '';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '' : new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: 'long', day: 'numeric' }).format(date);
};
export function voucherState(v: { status: number; type: number; stock?: number; beginTime?: string; endTime?: string }, now: number) {
  if (v.status !== 1) return '暂不可用';
  if (!v.type) return '查看规则';
  if (v.endTime && new Date(v.endTime).getTime() <= now) return '已结束';
  if (v.beginTime && new Date(v.beginTime).getTime() > now) return '未开始';
  if (v.stock !== undefined && v.stock < 1) return '已抢光';
  return '立即抢购';
}
