import { useState, type ReactNode } from 'react';
import * as Dialog from '@radix-ui/react-dialog';
import { ArrowClockwise, ImageSquare, MagnifyingGlass, Star, WarningCircle, X } from '@phosphor-icons/react';

export function Photo({ src, alt, className = '', eager = false, thumbnail = false }: { src?: string; alt: string; className?: string; eager?: boolean; thumbnail?: boolean }) {
  const [failed, setFailed] = useState(false);
  const [lastSrc, setLastSrc] = useState(src);
  if (lastSrc !== src) { setLastSrc(src); setFailed(false); }
  if (!src || failed) return <div className={`photo-placeholder ${className}`} role="img" aria-label={`${alt}，图片暂不可用`}><ImageSquare size={32} /><span>图片暂不可用</span></div>;
  const hasSizes = thumbnail && /^\/images\/(lifestyle|blog)-\d+\.webp$/.test(src);
  return <img src={src} srcSet={hasSizes ? `${src.replace('.webp', '-small.webp')} 400w, ${src} 800w` : undefined} sizes={hasSizes ? '(max-width: 767px) 100vw, 320px' : undefined} alt={alt} className={className} loading={eager ? 'eager' : 'lazy'} fetchPriority={eager ? 'high' : undefined} decoding="async" onError={() => setFailed(true)} />;
}

export function Rating({ score, comments, compact = false }: { score?: number; comments?: number; compact?: boolean }) {
  return <div className={`rating ${compact ? 'compact' : ''}`}>
    <span className="stars" role="img" aria-label={score !== undefined ? `评分 ${(score / 10).toFixed(1)}，满分5分` : '暂无评分'}>
      {Array.from({ length: compact ? 1 : 5 }, (_, i) => <Star key={i} size={compact ? 16 : 17} weight={score && i < Math.round(score / 10) ? 'fill' : 'regular'} aria-hidden="true" />)}
    </span>
    <strong>{score !== undefined ? (score / 10).toFixed(1) : '暂无评分'}</strong>
    {comments !== undefined && <span className="muted">{comments.toLocaleString('zh-CN')} 条评价</span>}
  </div>;
}

export function Loading({ rows = false, count = 4 }: { rows?: boolean; count?: number }) {
  return <div role="status" aria-label="正在加载" className={rows ? 'skeleton-list' : 'shop-grid'}>
    {Array.from({ length: count }, (_, i) => <div className={`skeleton-card ${rows ? 'skeleton-row' : ''}`} key={i}><div className="skeleton-image" /><div className="skeleton-content"><div /><div /><div /></div></div>)}
    <span className="sr-only">正在加载，请稍候</span>
  </div>;
}

export function Empty({ title = '暂时没有相关商家', description = '试试其他分类，或换个关键词搜索。', children }: { title?: string; description?: string; children?: ReactNode }) {
  return <div className="empty-state"><MagnifyingGlass size={40} weight="duotone" /><h3>{title}</h3><p>{description}</p>{children}</div>;
}

export function ErrorState({ message, retry, preview }: { message: string; retry: () => void; preview?: () => void }) {
  return <div className="error-state" role="alert"><WarningCircle size={32} /><h3>内容暂时无法加载</h3><p>{message}</p><div className="button-row"><button className="button secondary" onClick={retry}><ArrowClockwise size={17} />重新加载</button>{preview && <button className="button primary" onClick={preview}>查看设计预览</button>}</div></div>;
}

export function Modal({ open, onOpenChange, title, description, children, wide = false }: { open: boolean; onOpenChange: (open: boolean) => void; title: string; description: string; children: ReactNode; wide?: boolean }) {
  return <Dialog.Root open={open} onOpenChange={onOpenChange}><Dialog.Portal>
    <Dialog.Overlay className="dialog-overlay" />
    <Dialog.Content className={`dialog-content ${wide ? 'dialog-wide' : ''}`}>
      <Dialog.Title className="dialog-title">{title}</Dialog.Title>
      <Dialog.Description className="dialog-description">{description}</Dialog.Description>
      <Dialog.Close className="icon-button dialog-close" aria-label="关闭弹窗"><X size={21} /></Dialog.Close>
      {children}
    </Dialog.Content>
  </Dialog.Portal></Dialog.Root>;
}
