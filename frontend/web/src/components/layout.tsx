import { Suspense, useEffect, useState, type FormEvent } from 'react';
import { ArrowRight, CaretDown, Code, MagnifyingGlass, MapPin, Moon, NotePencil, Storefront, Sun, UserCircle } from '@phosphor-icons/react';
import { Link, NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useApp } from '../app-context';
import { LoginDialog } from './auth/login-dialog';
import { Loading, Modal } from './ui';
import { seckillOpsEnabled } from '../lib/local-tools';

export function Brand({ footer = false }: { footer?: boolean }) {
  return <Link to="/" className={`brand ${footer ? 'footer-brand' : ''}`}><span className="brand-mark"><Storefront size={29} weight="fill" /></span><span><strong>黑马点评</strong><small>发现生活的好去处</small></span></Link>;
}

export function Layout() {
  const { mode, setMode, theme, toggleTheme, user, setLoginOpen } = useApp();
  const location = useLocation();
  const navigate = useNavigate();
  const [search, setSearch] = useState('');
  const [cityOpen, setCityOpen] = useState(false);
  useEffect(() => { setSearch(new URLSearchParams(location.search).get('q') || ''); }, [location.search]);
  useEffect(() => {
    if (!location.hash) window.scrollTo(0, 0);
    else requestAnimationFrame(() => document.getElementById(location.hash.slice(1))?.scrollIntoView());
  }, [location.pathname, location.hash]);
  const submit = (event: FormEvent) => {
    event.preventDefault();
    navigate(search.trim() ? `/shops?q=${encodeURIComponent(search.trim())}` : '/shops');
  };
  return <>
    <a className="skip-link" href="#main-content">跳到主要内容</a>
    <div className="utility-bar"><div className="container utility-inner"><span>好店值得发现，生活值得期待</span><div><button onClick={() => setMode(mode === 'preview' ? 'live' : 'preview')}><Code size={14} />{mode === 'preview' ? '切换实时数据' : '设计预览'}</button><span className="utility-divider" /><button onClick={toggleTheme}><span>{theme === 'light' ? <Moon size={15} /> : <Sun size={15} />}</span>{theme === 'light' ? '深色模式' : '浅色模式'}</button></div></div></div>
    <header className="site-header"><div className="container header-main">
      <Brand />
      <button className="location-button" onClick={() => setCityOpen(true)} aria-label="查看当前城市杭州"><MapPin size={18} weight="fill" />杭州<CaretDown size={12} /></button>
      <form className="global-search" onSubmit={submit} role="search"><label htmlFor="global-search" className="sr-only">搜索商家</label><MagnifyingGlass size={21} /><input id="global-search" type="search" value={search} onChange={(event) => setSearch(event.target.value)} placeholder="搜索商家，发现想去的好店" autoComplete="off" /><button type="submit">搜索</button></form>
      <button className="account-button" aria-label={user ? `${user.nickName}的个人中心` : '登录 / 注册'} onClick={() => user ? navigate('/account') : setLoginOpen(true)}><UserCircle size={27} /><span>{user?.nickName || '登录 / 注册'}</span></button>
    </div><nav className={`container main-nav${seckillOpsEnabled ? ' has-local-tools' : ''}`} aria-label="主导航"><NavLink to="/" end className={({ isActive }) => isActive && !location.hash ? 'active' : ''}>首页</NavLink><NavLink to="/shops">发现好店</NavLink><NavLink to="/stories">探店笔记</NavLink><NavLink to="/account">个人中心</NavLink>{seckillOpsEnabled && <NavLink to="/local/seckill">秒杀维护</NavLink>}<Link className="nav-publish" to="/stories/new"><span><NotePencil size={16} />发布笔记</span></Link></nav></header>
    {mode === 'preview' && <div className="preview-banner"><div className="container"><Code size={15} /><span>设计预览：资料来自项目示例库，部分图片为示意；发布、点赞等操作需连接后端。</span><button onClick={() => setMode('live')}>连接后端 <ArrowRight size={14} /></button></div></div>}
    <main id="main-content" tabIndex={-1}><Suspense fallback={<div className="container route-loading"><Loading /></div>}><Outlet /></Suspense></main>
    <footer className="site-footer"><div className="container footer-main"><Brand footer /><p>从一顿好饭、一次相聚开始，<br />发现身边每一种值得期待的生活。</p><div className="footer-links"><Link to="/shops">发现好店</Link><Link to="/stories">探店笔记</Link><a href="#main-content">返回顶部</a></div></div><div className="container footer-bottom"><span>黑马点评 · 本地生活发现平台</span><span>认真生活，慢慢发现。</span></div></footer>
    <LoginDialog />
    <Modal open={cityOpen} onOpenChange={setCityOpen} title="当前城市：杭州" description="当前商家资料以杭州为主。"><p>通过商家分类或名称搜索，发现想去的店。附近商家查询可在列表页使用定位。</p><button className="button primary full-width" onClick={() => setCityOpen(false)}>开始探索</button></Modal>
  </>;
}
