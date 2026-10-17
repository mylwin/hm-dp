import { useCallback, useState } from 'react';
import { ArrowRight, CaretRight, ChatsCircle, MapPin, Storefront, Ticket } from '@phosphor-icons/react';
import { Link } from 'react-router-dom';
import { useApp } from '../context';
import { useResource } from '../lib/use-resource';
import { CategoryIcon } from '../components/category-icon';
import { Empty, ErrorState, Loading, Photo, ShopCard } from '../components/ui';
import { BlogCard } from '../components/community';

export default function Home() {
  const { catalog, setMode } = useApp();
  const [category, setCategory] = useState('1');
  const types = useResource(useCallback((signal) => catalog.types(signal), [catalog]));
  const shops = useResource(useCallback((signal) => catalog.shops({ typeId: category, current: 1 }, signal), [catalog, category]));
  const blogs = useResource(useCallback((signal) => catalog.blogs(signal), [catalog]));
  return <div className="container home-page">
    <section className="home-showcase" aria-label="探索本地生活">
      <aside className="category-panel"><div className="category-panel-heading"><Storefront size={19} />全部分类</div><div className="category-menu">
        {types.loading ? <div className="category-loading">分类加载中…</div> : types.error ? <button className="category-retry" onClick={types.reload}>分类加载失败，点击重试</button> : types.data?.slice(0, 7).map((type) => <Link key={type.id} to={`/shops?type=${type.id}`}><CategoryIcon id={type.id} /><span>{type.name}</span><CaretRight size={13} /></Link>)}
      </div></aside>
      <div className="hero"><Photo src="/images/dining-hero.webp" alt="窗边茶点与中式菜肴，城市里的日常美味" eager /><div className="hero-scrim" /><div className="hero-copy"><span className="hero-eyebrow">城市里的烟火气</span><h1>好好吃饭，<br />好好生活。</h1><p>发现街角好店，把日常过得有滋有味。</p><Link className="button hero-button" to="/shops?type=1">发现好店<ArrowRight size={18} /></Link></div></div>
      <Link to="/stories" className="weekend-card"><div><span className="weekend-caption">给生活一点新鲜感</span><h2>下一站，<br />去喜欢的地方。</h2><p>跟着探店笔记<br />找到下一次相聚的灵感</p><span className="text-link">看看大家去哪儿 <ArrowRight size={16} /></span></div><Photo src="/images/lifestyle-2.webp" alt="餐厅里刚刚上桌的牛排" eager /></Link>
    </section>
    <div className="discovery-strip"><div><span className="strip-icon"><MapPin size={24} /></span><span><strong>好店就在身边</strong><small>按分类发现你的下一站</small></span></div><div><span className="strip-icon"><ChatsCircle size={24} /></span><span><strong>先看看，再出发</strong><small>从探店分享里找点灵感</small></span></div><div><span className="strip-icon"><Ticket size={24} /></span><span><strong>到店前，看看优惠</strong><small>商家代金券与限时抢购</small></span></div></div>
    <section className="home-shops" aria-labelledby="shops-title"><div className="section-heading"><div><h2 id="shops-title">发现身边好店</h2><p>熟悉的街道，也藏着新的惊喜。</p></div><Link className="text-link" to={`/shops?type=${category}`}>全部商家<ArrowRight size={17} /></Link></div>
      <div className="category-tabs" role="group" aria-label="选择商家分类">{types.data?.map((type) => <button key={type.id} className={String(type.id) === category ? 'selected' : ''} aria-pressed={String(type.id) === category} onClick={() => setCategory(String(type.id))}><CategoryIcon id={type.id} size={18} />{type.name}</button>)}</div>
      {shops.loading ? <Loading /> : shops.error ? <ErrorState message={shops.error} retry={shops.reload} preview={() => setMode('preview')} /> : !shops.data?.length ? <Empty /> : <div className="shop-grid">{shops.data.slice(0, 4).map((shop) => <ShopCard key={shop.id} shop={shop} />)}</div>}
    </section>
    <section className="stories-section" id="stories" aria-labelledby="stories-title"><div className="section-heading"><div><h2 id="stories-title">跟着笔记去探店</h2><p>那些值得分享的小店，和认真生活的人。</p></div><Link className="text-link" to="/stories">更多笔记<ArrowRight size={17} /></Link></div>
      {blogs.loading ? <Loading count={2} /> : blogs.error ? <ErrorState message={blogs.error} retry={blogs.reload} /> : !blogs.data?.length ? <Empty title="还没有探店笔记" description="先从发现身边的好店开始吧。" /> : <div className="story-grid">{blogs.data.slice(0, 2).map((blog) => <BlogCard blog={blog} key={blog.id} editorial />)}</div>}
    </section>
  </div>;
}
