import { useCallback, useEffect, useState, type ReactNode } from 'react';
import { ArrowRight, Heart, NotePencil, SignIn, UserPlus, Users } from '@phosphor-icons/react';
import { Link } from 'react-router-dom';
import { useApp } from '../context';
import { errorMessage, imagesOf, readableText } from '../lib/utils';
import { useResource } from '../lib/use-resource';
import type { Blog, Id, User } from '../types';
import { Empty, Loading, Photo } from './ui';

export function Avatar({ user, size = 40 }: { user?: { nickName?: string; name?: string; icon?: string } | null; size?: number }) {
  const [failed, setFailed] = useState('');
  const name = user?.nickName || user?.name || '探店用户';
  return <span className="avatar" style={{ width: size, height: size, fontSize: Math.max(14, size * .35) }} aria-hidden="true">{user?.icon && failed !== user.icon ? <img src={user.icon} alt="" onError={() => setFailed(user.icon!)} /> : name.slice(0, 1).toUpperCase()}</span>;
}

export function BlogCard({ blog, editorial = false }: { blog: Blog; editorial?: boolean }) {
  return <Link className={editorial ? 'story-card' : 'journal-card'} to={`/stories/${blog.id}`}>
    <Photo src={imagesOf(blog.images)[0]} alt={blog.title} thumbnail />
    <div className={editorial ? 'story-card-content' : 'journal-card-content'}>
      {editorial && <span className="story-category">探店分享</span>}<h3>{blog.title}</h3><p>{readableText(blog.content).trim().slice(0, 90)}</p>
      <div className="story-byline"><span className="author-inline"><Avatar user={blog} size={24} />{blog.name || '探店用户'}</span><span><Heart size={15} />{blog.liked ?? 0}</span></div>
    </div>
  </Link>;
}

export function AuthGate({ children, title = '登录后，继续发现好生活', description = '查看探店详情、发布笔记，与喜欢生活的人保持连接。' }: { children: ReactNode; title?: string; description?: string }) {
  const { user, mode, authLoading, setLoginOpen } = useApp();
  useEffect(() => { if (mode === 'live' && !user && !authLoading) document.title = `${title} - 黑马点评`; }, [mode, user, authLoading, title]);
  if (mode === 'preview') return children;
  if (authLoading) return <div className="container protected-loading"><Loading count={2} /></div>;
  if (!user) return <div className="container auth-gate"><span className="gate-icon"><Users size={38} weight="duotone" /></span><h1>{title}</h1><p>{description}</p><button className="button primary" onClick={() => setLoginOpen(true)}><SignIn size={18} />登录 / 注册</button><Link className="text-link" to="/shops">先逛逛商家<ArrowRight size={16} /></Link></div>;
  return children;
}

export function FollowButton({ id }: { id: Id }) {
  const { community, mode, user, setLoginOpen } = useApp();
  const following = useResource(useCallback((signal) => mode === 'live' && user && String(user.id) !== String(id) ? community.isFollowing(id, signal) : Promise.resolve(false), [community, id, mode, user]));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  if (String(user?.id) === String(id)) return <Link className="button secondary" to="/account">我的主页</Link>;
  const toggle = async () => {
    if (mode === 'preview' || busy) return;
    if (!user) { setLoginOpen(true); return; }
    setBusy(true); setError('');
    try { await community.follow(id, !following.data); following.reload(); }
    catch (error) { setError(errorMessage(error)); }
    finally { setBusy(false); }
  };
  return <div className="follow-control"><button className={`button ${following.data ? 'secondary' : 'primary'}`} onClick={toggle} disabled={mode === 'preview' || busy || following.loading || !!following.error}><UserPlus size={17} />{busy ? '正在更新' : following.data ? '已关注' : '关注作者'}</button>{(error || following.error) && <p className="form-error" role="alert">{error || following.error}{following.error && <button onClick={following.reload}>重试</button>}</p>}</div>;
}

export function PeopleRow({ users }: { users: User[] }) {
  return <div className="people-row">{users.map((user) => <Link key={user.id} to={`/people/${user.id}`} title={user.nickName}><Avatar user={user} size={32} /><span className="sr-only">{user.nickName}</span></Link>)}</div>;
}

export function WritePrompt() {
  return <div className="write-prompt"><span className="aside-icon"><NotePencil size={27} /></span><h2>好店值得发现，<br />也值得被分享。</h2><p>记下一次用餐，<br />给下一位出发的人一点灵感。</p><Link className="button primary full-width" to="/stories/new"><NotePencil size={17} />发布笔记</Link></div>;
}

export function EmptyNotes({ mine = false }: { mine?: boolean }) {
  return <Empty title={mine ? '第一篇笔记，从这里开始' : '暂时还没有探店笔记'} description={mine ? '用照片和文字，记下一次值得分享的体验。' : '先逛逛商家，寻找下一次出门的灵感。'}><Link className="button secondary" to={mine ? '/stories/new' : '/shops'}>{mine ? '发布笔记' : '发现好店'}<ArrowRight size={16} /></Link></Empty>;
}
