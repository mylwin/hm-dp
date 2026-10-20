import { useCallback, useEffect, useState } from 'react';
import { ArrowRight, CaretLeft, CaretRight, Copy, Eye, Heart, MapPin, NotePencil } from '@phosphor-icons/react';
import { Link, useParams } from 'react-router-dom';
import { AuthGate, Avatar, FollowButton, PeopleRow } from '../components/community/index';
import { ErrorState, Loading, Modal, Photo, Rating } from '../components/ui';
import { useApp } from '../app-context';
import { useResource } from '../hooks/use-resource';
import { dateLabel, errorMessage, imagesOf, readableText } from '../lib/utils';
import type { Blog } from '../types';

export default function StoryDetail() {
  const { id = '' } = useParams();
  const { mode } = useApp();
  return <AuthGate title="登录后，阅读这篇探店笔记"><StoryContent key={`${mode}:${id}`} id={id} /></AuthGate>;
}

function StoryContent({ id }: { id: string }) {
  const { community, catalog, mode } = useApp();
  const note = useResource(useCallback((signal) => community.blog(id, signal), [community, id]));
  const likes = useResource(useCallback((signal) => community.likes(id, signal), [community, id]));
  const [updated, setUpdated] = useState<Blog>();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [needsSync, setNeedsSync] = useState(false);
  const [copyNotice, setCopyNotice] = useState('');
  const [imageIndex, setImageIndex] = useState(0);
  const [lightbox, setLightbox] = useState(false);
  const blog = updated || note.data;
  const shop = useResource(useCallback((signal) => blog?.shopId ? catalog.shop(blog.shopId, signal) : Promise.resolve(null), [catalog, blog?.shopId]));
  useEffect(() => { if (blog) document.title = `${blog.title} - 黑马点评`; }, [blog]);
  const toggleLike = async () => {
    if (mode === 'preview' || busy) return;
    setBusy(true); setError('');
    let submitted = false;
    try {
      await community.like(id); submitted = true;
      setUpdated(await community.blog(id)); likes.reload();
    } catch (error) {
      setNeedsSync(submitted);
      setError(submitted ? '操作已提交，暂时无法刷新点赞状态。请重新同步后再操作。' : errorMessage(error));
    } finally { setBusy(false); }
  };
  const sync = async () => {
    setBusy(true);
    try { setUpdated(await community.blog(id)); setNeedsSync(false); setError(''); likes.reload(); }
    catch (error) { setError(errorMessage(error)); }
    finally { setBusy(false); }
  };
  const copy = async () => {
    const url = new URL(location.href);
    if (mode === 'preview') url.searchParams.set('preview', '1');
    try { await navigator.clipboard.writeText(url.href); setCopyNotice('笔记链接已复制'); }
    catch { setCopyNotice('暂时无法复制，请复制浏览器地址栏中的链接'); }
  };
  if (note.loading) return <div className="container community-page"><Loading count={2} /></div>;
  if (note.error || !blog) return <div className="container community-page"><ErrorState message={note.error || '笔记暂时无法查看'} retry={note.reload} /></div>;
  const images = imagesOf(blog.images);
  const active = Math.min(imageIndex, Math.max(0, images.length - 1));
  return <div className="container community-page reading-page"><div className="breadcrumb"><Link to="/">首页</Link><CaretRight size={12} /><Link to="/stories">探店笔记</Link><CaretRight size={12} /><span>笔记详情</span></div>
    <div className="community-layout reading-layout"><article className="reading-article"><header className="article-header"><Link className="article-author" to={`/people/${blog.userId}`}><Avatar user={blog} size={43} /><span><strong>{blog.name || '探店用户'}</strong><small>{dateLabel(blog.createTime) || '探店分享'}</small></span></Link><button className="icon-button" aria-label="复制笔记链接" onClick={copy}><Copy size={19} /></button></header>
      <h1>{blog.title}</h1><div className="article-gallery"><button className="article-main-image" onClick={() => setLightbox(true)} disabled={!images.length} aria-label="放大笔记照片"><Photo src={images[active]} alt={`${blog.title}，照片 ${active + 1}`} eager /></button><div className="article-image-caption"><span>{images.length ? `${active + 1} / ${images.length} 张照片` : '暂无照片'}</span><button onClick={() => setLightbox(true)} disabled={!images.length}><Eye size={16} />查看大图</button></div>{images.length > 1 && <div className="note-thumbnails">{images.map((src, index) => <button key={`${src}-${index}`} onClick={() => setImageIndex(index)} className={active === index ? 'selected' : ''} aria-label={`查看笔记照片 ${index + 1}`} aria-pressed={active === index}><Photo src={src} alt={`笔记缩略图 ${index + 1}`} thumbnail /></button>)}</div>}</div>
      <div className="article-copy">{readableText(blog.content).split(/\n{2,}/).map((paragraph, i) => <p key={i}>{paragraph}</p>)}</div>
      <footer className="article-actions"><button className={`button ${blog.isLike ? 'primary' : 'secondary'}`} disabled={mode === 'preview' || busy || needsSync} onClick={toggleLike} aria-pressed={!!blog.isLike}><Heart size={19} weight={blog.isLike ? 'fill' : 'regular'} />{busy ? '正在更新' : blog.isLike ? '已点赞' : '点赞'}<span>{blog.liked ?? 0}</span></button><button className="button secondary" onClick={copy}><Copy size={17} />复制链接</button><span className="article-end-label">每一份认真分享，都值得被看见。</span></footer>
      {error && <p role="alert" className="form-error">{error}{needsSync && <button onClick={sync} disabled={busy}>重新同步</button>}</p>}{copyNotice && <p role="status" className="copy-notice">{copyNotice}</p>}
      {!!likes.data?.length && <div className="liked-by"><PeopleRow users={likes.data} /><span>这些用户也喜欢这篇笔记</span></div>}
    </article><aside className="community-aside"><section className="author-card"><Link to={`/people/${blog.userId}`}><Avatar user={blog} size={65} /><h2>{blog.name || '探店用户'}</h2><p>记录每一个值得分享的去处。</p></Link><FollowButton id={blog.userId} /><Link className="text-link" to={`/people/${blog.userId}`}>查看作者主页<ArrowRight size={16} /></Link></section>
      <section className="related-shop"><h2>这篇笔记里的店</h2>{shop.loading ? <p className="muted">商家信息加载中…</p> : shop.error ? <p className="muted">商家信息暂不可用<button className="text-link" onClick={shop.reload}>重试</button></p> : shop.data ? <Link to={`/shops/${shop.data.id}`}><Photo src={imagesOf(shop.data.images)[0]} alt={shop.data.name} thumbnail /><h3>{shop.data.name}</h3><Rating score={shop.data.score} compact /><p><MapPin size={14} />{shop.data.area}</p><span className="text-link">查看商家与优惠<ArrowRight size={16} /></span></Link> : <p className="muted">暂未关联商家</p>}</section>
      <Link className="aside-story-link" to="/stories/new"><span>也有好店想分享？<strong>发布你的探店笔记</strong></span><NotePencil size={22} /></Link></aside></div>
    <Modal open={lightbox} onOpenChange={setLightbox} title="探店相册" description={`共 ${images.length} 张照片，当前第 ${active + 1} 张。`} wide><div className="lightbox-photo"><Photo src={images[active]} alt={`${blog.title}，照片 ${active + 1}`} eager /></div><div className="lightbox-controls"><button className="button secondary" disabled={images.length < 2} onClick={() => setImageIndex((active - 1 + images.length) % images.length)}><CaretLeft size={17} />上一张</button><span>{active + 1} / {images.length}</span><button className="button secondary" disabled={images.length < 2} onClick={() => setImageIndex((active + 1) % images.length)}>下一张<CaretRight size={17} /></button></div></Modal>
  </div>;
}
