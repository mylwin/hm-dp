import { useCallback, useEffect, useState } from 'react';
import { ArrowLeft, ArrowRight, CaretRight, Compass, ListBullets, MapPin, SquaresFour, X } from '@phosphor-icons/react';
import { Link, useSearchParams } from 'react-router-dom';
import { useApp } from '../context';
import { useResource } from '../lib/use-resource';
import { CategoryIcon } from '../components/category-icon';
import { Empty, ErrorState, Loading, ShopCard } from '../components/ui';

export default function Shops() {
  const { catalog, mode, setMode } = useApp();
  const [params, setParams] = useSearchParams();
  const type = params.get('type') || '';
  const name = params.get('q')?.trim() || '';
  const rawPage = Number(params.get('page'));
  const current = Number.isSafeInteger(rawPage) && rawPage > 0 ? rawPage : 1;
  const [layout, setLayout] = useState<'list' | 'grid'>('list');
  const [position, setPosition] = useState<{ x: number; y: number }>();
  const [locationError, setLocationError] = useState('');
  const [locating, setLocating] = useState(false);
  const types = useResource(useCallback((signal) => catalog.types(signal), [catalog]));
  const shops = useResource(useCallback((signal) => catalog.shops({ typeId: type || undefined, name: name || undefined, current, ...position }, signal), [catalog, type, name, current, position]));
  const typeName = types.data?.find((item) => String(item.id) === type)?.name;
  const pageSize = type && !name ? 5 : 10;
  const hasNext = (shops.data?.length || 0) === pageSize;
  useEffect(() => { setPosition(undefined); setLocationError(''); }, [type, name, mode]);
  useEffect(() => { document.title = `${name ? `${name} · 搜索` : typeName || '发现好店'} - 黑马点评`; }, [name, typeName]);
  const update = (values: Record<string, string>) => {
    const next = new URLSearchParams(params);
    for (const [key, value] of Object.entries(values)) value ? next.set(key, value) : next.delete(key);
    setParams(next);
  };
  const locate = () => {
    if (position) { setPosition(undefined); update({ page: '' }); return; }
    if (!type || name) { setLocationError('请先选择一种分类，再查看附近商家。'); return; }
    if (!navigator.geolocation) { setLocationError('当前浏览器不支持定位。'); return; }
    setLocating(true); setLocationError('');
    navigator.geolocation.getCurrentPosition(({ coords }) => {
      setPosition({ x: coords.longitude, y: coords.latitude }); setLocating(false); update({ page: '' });
    }, () => { setLocationError('未能获取位置，请检查浏览器定位权限。'); setLocating(false); }, { timeout: 10000, maximumAge: 60000 });
  };
  return <div className="container listing-page">
    <div className="breadcrumb"><Link to="/">首页</Link><CaretRight size={12} /><span>{name ? '搜索结果' : '发现好店'}</span>{typeName && <><CaretRight size={12} /><span>{typeName}</span></>}</div>
    <div className="page-heading"><h1>{name ? `“${name}”的搜索结果` : typeName ? `发现${typeName}好店` : '下一家好店，就在这里。'}</h1><p>{name ? '找到心仪商家，看看图片、评价和到店优惠。' : '从想吃的、想玩的开始，找到属于你的好去处。'}</p></div>
    <section className="filter-panel" aria-label="商家筛选"><div className="filter-row"><span className="filter-label">商家分类</span><div className="filter-options"><button aria-pressed={!type} className={!type ? 'selected' : ''} onClick={() => update({ type: '', q: '', page: '' })}>全部</button>{types.data?.map((item) => <button key={item.id} className={type === String(item.id) ? 'selected' : ''} aria-pressed={type === String(item.id)} onClick={() => update({ type: String(item.id), q: '', page: '' })}>{item.name}</button>)}{types.error && <button onClick={types.reload}>分类加载失败，重试</button>}</div></div>
      <div className="filter-summary"><span>已选条件</span><div>{typeName || name ? <>{typeName && <button className="filter-chip" onClick={() => update({ type: '', page: '' })}>{typeName}<X size={13} /></button>}{name && <button className="filter-chip" onClick={() => update({ q: '', page: '' })}>{name}<X size={13} /></button>}</> : <span className="muted">全部商家</span>}</div>{(type || name) && <button className="clear-filter" onClick={() => { setPosition(undefined); update({ type: '', q: '', page: '' }); }}>清空条件</button>}</div>
    </section>
    <div className="listing-layout"><section className="listing-results" aria-label="商家列表"><div className="results-toolbar"><span><strong>{position ? '附近商家' : '商家列表'}</strong><span className="muted">第 {current} 页</span></span><div><button className={`nearby-button ${position ? 'selected' : ''}`} onClick={locate} disabled={locating || mode === 'preview'} title={mode === 'preview' ? '连接后端后可使用定位' : '按你的真实位置查找附近商家'}><Compass size={17} />{locating ? '定位中' : position ? '取消附近' : '附近商家'}</button><div className="view-switch" role="group" aria-label="列表显示方式"><button aria-label="列表视图" aria-pressed={layout === 'list'} className={layout === 'list' ? 'selected' : ''} onClick={() => setLayout('list')}><ListBullets size={20} /></button><button aria-label="网格视图" aria-pressed={layout === 'grid'} className={layout === 'grid' ? 'selected' : ''} onClick={() => setLayout('grid')}><SquaresFour size={20} /></button></div></div></div>
      {locationError && <p className="inline-message" role="status">{locationError}</p>}
      {shops.loading ? <Loading rows={layout === 'list'} count={layout === 'list' ? 3 : 4} /> : shops.error ? <ErrorState message={shops.error} retry={shops.reload} preview={() => setMode('preview')} /> : !shops.data?.length ? <Empty description={current > 1 ? '已经浏览到最后一页，返回上一页继续看看。' : '当前条件下没有商家，试试切换分类或关键词。'}><button className="button secondary" onClick={() => current > 1 ? update({ page: String(current - 1) }) : update({ type: '', q: '', page: '' })}>{current > 1 ? '返回上一页' : '查看全部商家'}</button></Empty> : <div className={layout === 'list' ? 'shop-list' : 'shop-grid listing-grid'}>{shops.data.map((shop) => <ShopCard key={shop.id} shop={shop} row={layout === 'list'} />)}</div>}
      <nav className="pagination" aria-label="商家列表分页"><button className="button secondary" disabled={current <= 1 || shops.loading} onClick={() => update({ page: String(current - 1) })}><ArrowLeft size={16} />上一页</button><span aria-current="page">{current}</span><button className="button secondary" disabled={!hasNext || shops.loading || !!shops.error} onClick={() => { update({ page: String(current + 1) }); window.scrollTo({ top: 200, behavior: 'instant' }); }}>下一页<ArrowRight size={16} /></button></nav>
    </section><aside className="listing-aside"><div className="outing-guide"><span className="aside-icon"><MapPin size={28} /></span><h2>每次出门，<br />都值得期待。</h2><p>先看看商家相册，<br />再找一张到店优惠券。</p><div className="aside-divider" /><strong>发现好店的小提示</strong><ul><li>按分类缩小寻找范围</li><li>看看评分与人均消费</li><li>出发前确认营业时间</li></ul></div><Link to="/stories" className="aside-story-link"><span>没有灵感？<strong>去探店笔记看看</strong></span><ArrowRight size={21} /></Link></aside></div>
  </div>;
}
