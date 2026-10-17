import { useCallback, useEffect, useRef, useState } from 'react';
import { ArrowRight, Article, CalendarCheck, CaretRight, Info, MapPin, NotePencil, SignOut, UserCircle, Users } from '@phosphor-icons/react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { AuthGate, Avatar, BlogCard, EmptyNotes, FollowButton, PeopleRow } from '../components/community';
import { Empty, ErrorState, Loading } from '../components/ui';
import { useApp } from '../context';
import { auth } from '../lib/api';
import { useResource } from '../lib/use-resource';
import { errorMessage } from '../lib/utils';
import type { Blog, FeedCursor, FeedResult, Id } from '../types';

export default function Profile() {
  const { id } = useParams();
  const { user, mode } = useApp();
  const ownerId = id || (mode === 'preview' ? '2' : String(user?.id || ''));
  return <AuthGate title="登录后，打开你的生活记录"><ProfileContent key={`${mode}:${ownerId}`} ownerId={ownerId} mine={!id || String(user?.id) === id} /></AuthGate>;
}

function FollowingFeed() {
  const { community, mode } = useApp();
  const [items, setItems] = useState<Blog[]>([]);
  const [busy, setBusy] = useState(true);
  const [error, setError] = useState('');
  const [hasMore, setHasMore] = useState(false);
  const cursor = useRef<FeedCursor>({ lastId: Date.now(), offset: 0 });
  const inFlight = useRef(false);
  const apply = (result: FeedResult | null, current: FeedCursor) => {
    const list = result?.list || [];
    setItems((previous) => [...new Map([...previous, ...list].map((blog) => [String(blog.id), blog])).values()]);
    setHasMore(list.length === 3);
    if (result && list.length) cursor.current = { lastId: result.minTime, offset: result.minTime === current.lastId ? current.offset + result.offset : result.offset };
  };
  useEffect(() => {
    const controller = new AbortController();
    const current = { lastId: Date.now(), offset: 0 }; cursor.current = current;
    community.feed(current, controller.signal).then((result) => {
      if (!controller.signal.aborted) { apply(result, current); setBusy(false); }
    }).catch((error) => { if (!controller.signal.aborted) { setError(errorMessage(error)); setBusy(false); } });
    return () => controller.abort();
  }, [community]);
  const more = async () => {
    if (inFlight.current) return;
    inFlight.current = true; setBusy(true); setError('');
    try { const current = cursor.current; apply(await community.feed(current), current); }
    catch (error) { setError(errorMessage(error)); }
    finally { inFlight.current = false; setBusy(false); }
  };
  return <><div className="profile-section-heading"><h2>关注动态</h2><span>你关注的作者，最近的分享</span></div>{!items.length && busy ? <Loading count={3} /> : !items.length && error ? <ErrorState message={error} retry={more} /> : !items.length ? <Empty title="还没有新的关注动态" description={mode === 'preview' ? '预览模式仅展示页面结构，连接后端后查看真实动态。' : '到探店笔记中关注喜欢的作者，新分享会出现在这里。'}><Link className="button secondary" to="/stories">去看看探店笔记<ArrowRight size={16} /></Link></Empty> : <><div className="profile-notes-grid">{items.map((blog) => <BlogCard key={blog.id} blog={blog} />)}</div>{error && <p className="form-error" role="alert">{error}</p>}{(hasMore || error) && <div className="feed-load-more"><button className="button secondary" onClick={more} disabled={busy}>{busy ? '正在加载' : error ? '重试加载' : '查看更多动态'}</button></div>}</>}</>;
}

function SignInCard() {
  const { community, mode } = useApp();
  const count = useResource(useCallback(() => mode === 'preview' ? Promise.resolve(null) : community.signCount(), [community, mode]));
  const [busy, setBusy] = useState(false);
  const [signed, setSigned] = useState(false);
  const [notice, setNotice] = useState('');
  const sign = async () => {
    if (mode === 'preview' || busy) return;
    setBusy(true); setNotice('');
    try { await community.sign(); setSigned(true); setNotice('签到成功，明天继续记录好生活。'); count.reload(); }
    catch (error) { setNotice(errorMessage(error)); if (errorMessage(error).includes('今日已签到')) { setSigned(true); count.reload(); } }
    finally { setBusy(false); }
  };
  const todaySigned = signed || (count.data ?? 0) > 0;
  return <div className="sign-card"><CalendarCheck size={25} /><strong>今天，也认真生活</strong><p>{mode === 'preview' ? '连接后端后，可记录每日签到。' : count.data !== undefined && count.data !== null ? `当前连续签到 ${count.data} 天` : '正在读取签到记录'}</p>{count.error && <button className="text-link" onClick={count.reload}>签到记录加载失败，重试</button>}<button className="button secondary full-width" onClick={sign} disabled={mode === 'preview' || busy || todaySigned || count.loading}>{busy ? '签到中' : todaySigned ? '今日已签到' : '每日签到'}</button>{notice && <p role="status" className="sign-notice">{notice}</p>}</div>;
}

function ProfileContent({ ownerId, mine }: { ownerId: Id; mine: boolean }) {
  const { community, user, setUser, mode } = useApp();
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const tab = ['notes', 'info', ...(mine ? ['feed'] : [])].includes(params.get('tab') || '') ? params.get('tab')! : 'notes';
  const raw = Number(params.get('page'));
  const current = Number.isSafeInteger(raw) && raw > 0 ? raw : 1;
  const person = useResource(useCallback((signal) => mine && mode === 'live' ? Promise.resolve(user) : community.user(ownerId, signal), [community, mine, mode, ownerId, user]));
  const info = useResource(useCallback((signal) => community.info(ownerId, signal), [community, ownerId]));
  const notes = useResource(useCallback((signal) => community.userBlogs(ownerId, current, mine, signal), [community, current, mine, ownerId]));
  const common = useResource(useCallback((signal) => mine ? Promise.resolve([]) : community.common(ownerId, signal), [community, mine, ownerId]));
  const [logoutBusy, setLogoutBusy] = useState(false);
  const [logoutError, setLogoutError] = useState('');
  useEffect(() => { document.title = `${mine ? '个人中心' : person.data?.nickName || '作者主页'} - 黑马点评`; }, [mine, person.data]);
  const logout = async () => {
    if (mode === 'preview' || logoutBusy) return;
    setLogoutBusy(true); setLogoutError('');
    try { await auth.logout(); sessionStorage.removeItem('token'); setUser(null); navigate('/'); }
    catch (error) { setLogoutError(errorMessage(error)); }
    finally { setLogoutBusy(false); }
  };
  const changeTab = (value: string) => setParams({ tab: value });
  if (person.loading) return <div className="container community-page"><Loading count={2} /></div>;
  if (person.error || !person.data) return <div className="container community-page"><ErrorState message={person.error || '这位用户暂时无法查看'} retry={person.reload} /></div>;
  const identity = person.data;
  return <div className="container community-page profile-page"><div className="breadcrumb"><Link to="/">首页</Link><CaretRight size={12} /><span>{mine ? '个人中心' : '作者主页'}</span></div>
    <section className="profile-header"><Avatar user={identity} size={84} /><div className="profile-identity"><span className="profile-caption">{mine ? '我的生活记录' : '探店作者'}</span><h1>{identity.nickName}</h1><p>{info.data?.introduce || '每一次值得分享的发现，都从认真生活开始。'}</p>{info.data?.city && <span className="profile-city"><MapPin size={14} />{info.data.city}</span>}</div><div className="profile-header-actions">{mine ? <Link className="button primary" to="/stories/new"><NotePencil size={18} />发布笔记</Link> : <FollowButton id={ownerId} />}{info.data && <div className="profile-stats">{info.data.fans !== undefined && <span><strong>{info.data.fans}</strong>粉丝</span>}{info.data.followee !== undefined && <span><strong>{info.data.followee}</strong>关注</span>}</div>}</div></section>
    <div className="profile-layout"><aside className="profile-sidebar"><nav className="profile-menu" aria-label={mine ? '个人中心导航' : '作者主页导航'}><button className={tab === 'notes' ? 'selected' : ''} aria-current={tab === 'notes' ? 'page' : undefined} onClick={() => changeTab('notes')}><Article size={19} />{mine ? '我的笔记' : '作者笔记'}<CaretRight size={13} /></button>{mine && <button className={tab === 'feed' ? 'selected' : ''} aria-current={tab === 'feed' ? 'page' : undefined} onClick={() => changeTab('feed')}><Users size={19} />关注动态<CaretRight size={13} /></button>}<button className={tab === 'info' ? 'selected' : ''} aria-current={tab === 'info' ? 'page' : undefined} onClick={() => changeTab('info')}><UserCircle size={19} />{mine ? '个人资料' : '作者资料'}<CaretRight size={13} /></button>{mine && <button className="logout-menu" onClick={logout} disabled={mode === 'preview' || logoutBusy}><SignOut size={19} />{logoutBusy ? '正在退出' : '退出登录'}</button>}</nav>{logoutError && <p className="form-error" role="alert">{logoutError}</p>}{mine ? <SignInCard /> : !!common.data?.length && <div className="common-follow"><strong>你们的共同关注</strong><PeopleRow users={common.data} /></div>}</aside>
      <section className="profile-content" aria-label={tab === 'feed' ? '关注动态' : tab === 'info' ? '个人资料' : '探店笔记'}>{tab === 'feed' ? <FollowingFeed /> : tab === 'info' ? <><div className="profile-section-heading"><h2>{mine ? '个人资料' : '作者资料'}</h2><span>让每一次相遇，都多一点了解</span></div>{info.error ? <ErrorState message={info.error} retry={info.reload} /> : <div className="profile-info-panel"><dl><div><dt>昵称</dt><dd>{identity.nickName}</dd></div><div><dt>所在城市</dt><dd>{info.data?.city || '暂未提供'}</dd></div><div className="wide-info"><dt>个人简介</dt><dd>{info.data?.introduce || '暂未填写个人简介'}</dd></div>{mine && info.data?.birthday && <div><dt>生日</dt><dd>{info.data.birthday}</dd></div>}{mine && info.data?.credits !== undefined && <div><dt>积分</dt><dd>{info.data.credits}</dd></div>}</dl><p className="profile-info-note"><Info size={16} />{mine ? '资料目前支持查看，编辑功能暂未开放。' : '这里仅展示作者提供的个人资料。'}</p></div>}</> : <><div className="profile-section-heading"><h2>{mine ? '我的笔记' : '作者笔记'}</h2><span>第 {current} 页{notes.data ? `，本页 ${notes.data.length} 篇` : ''}</span></div>{notes.loading ? <Loading count={3} /> : notes.error ? <ErrorState message={notes.error} retry={notes.reload} /> : !notes.data?.length ? <EmptyNotes mine={mine} /> : <div className="profile-notes-grid">{notes.data.map((blog) => <BlogCard blog={{ ...blog, name: blog.name || identity.nickName, icon: blog.icon || identity.icon }} key={blog.id} />)}</div>}<nav className="pagination" aria-label="作者笔记分页"><button className="button secondary" disabled={current === 1 || notes.loading} onClick={() => setParams({ tab: 'notes', page: String(current - 1) })}>上一页</button><span aria-current="page">{current}</span><button className="button secondary" disabled={notes.loading || !!notes.error || (notes.data?.length || 0) < 10} onClick={() => setParams({ tab: 'notes', page: String(current + 1) })}>下一页</button></nav></>}</section></div>
  </div>;
}
