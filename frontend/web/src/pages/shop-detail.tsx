import { useCallback, useEffect, useState } from 'react';
import { ArrowLeft, ArrowRight, CaretLeft, CaretRight, CheckCircle, Clock, Copy, Info, MapPin, Storefront, Ticket } from '@phosphor-icons/react';
import { Link, useLocation, useParams } from 'react-router-dom';
import { useApp } from '../app-context';
import { seckill } from '../api/vouchers';
import { useResource } from '../hooks/use-resource';
import { errorMessage, imagesOf, money, voucherState } from '../lib/utils';
import type { Id, Voucher } from '../types';
import { Empty, ErrorState, Modal, Photo, Rating } from '../components/ui';

function VoucherCard({ voucher, now, buy, preview }: { voucher: Voucher; now: number; buy: () => void; preview: boolean }) {
  const [expanded, setExpanded] = useState(false);
  const state = voucherState(voucher, now);
  const canBuy = state === '立即抢购';
  const rules = voucher.rules?.split(/\\+n|\r?\n|\\+/).map((line) => line.trim()).filter(Boolean) || [];
  return <article className="voucher-card"><div className="voucher-main"><div className="voucher-value"><span>¥<strong>{money(voucher.actualValue)}</strong></span><small>到店代金券</small></div><div className="voucher-description"><span className="voucher-kind"><Ticket size={14} />{voucher.type ? '限时抢购' : '商家优惠'}</span><h3>{voucher.title}</h3><p>{voucher.subTitle}</p><button className="rule-link" onClick={() => setExpanded(!expanded)} aria-expanded={expanded} aria-controls={`rules-${voucher.id}`}>{expanded ? '收起规则' : '使用规则'}<CaretRight size={12} /></button></div><div className="voucher-purchase"><span className="voucher-price">¥<strong>{money(voucher.payValue)}</strong></span>{voucher.type ? <><button className="button primary" disabled={!canBuy || preview} onClick={buy}>{preview ? '预览中不可抢购' : state}</button>{voucher.stock !== undefined && <small>剩余 {voucher.stock} 张</small>}</> : <span className="voucher-info-label">到店前请咨询商家</span>}</div></div>
    {voucher.type !== 0 && (voucher.beginTime || voucher.endTime) && <p className="voucher-time"><Clock size={14} />活动时间：{voucher.beginTime?.replace('T', ' ') || '未提供'} 至 {voucher.endTime?.replace('T', ' ') || '未提供'}</p>}
    {expanded && <div id={`rules-${voucher.id}`} className="voucher-rules"><strong>使用规则</strong>{rules.length ? <ul>{rules.map((rule, i) => <li key={i}>{rule}</li>)}</ul> : <p>商家暂未提供详细使用规则，请到店前咨询。</p>}</div>}
  </article>;
}

export default function ShopDetail() {
  const { id = '' } = useParams();
  const { hash } = useLocation();
  const { catalog, mode, setMode, user, setLoginOpen } = useApp();
  const shop = useResource(useCallback((signal) => catalog.shop(id, signal), [catalog, id]));
  const vouchers = useResource(useCallback((signal) => catalog.vouchers(id, signal), [catalog, id]));
  const types = useResource(useCallback((signal) => catalog.types(signal), [catalog]));
  const [activeImage, setActiveImage] = useState(0);
  const [galleryOpen, setGalleryOpen] = useState(false);
  const [now, setNow] = useState(Date.now());
  const [selected, setSelected] = useState<Voucher | null>(null);
  const [busy, setBusy] = useState(false);
  const [orderId, setOrderId] = useState<Id | null>(null);
  const [orderError, setOrderError] = useState('');
  const [copyNotice, setCopyNotice] = useState('');
  const [addressNotice, setAddressNotice] = useState('');
  useEffect(() => { const interval = setInterval(() => setNow(Date.now()), 1000); return () => clearInterval(interval); }, []);
  useEffect(() => { setActiveImage(0); setSelected(null); setOrderId(null); setOrderError(''); }, [id, mode]);
  useEffect(() => { if (!user) setSelected(null); }, [user]);
  useEffect(() => { if (shop.data) document.title = `${shop.data.name} - 黑马点评`; }, [shop.data]);
  const allImages = imagesOf(shop.data?.images);
  const currentImage = Math.min(activeImage, Math.max(0, allImages.length - 1));
  const category = types.data?.find((item) => String(item.id) === String(shop.data?.typeId));
  const copy = async (value: string, setMessage: (message: string) => void) => {
    try { await navigator.clipboard.writeText(value); setMessage('已复制'); }
    catch { setMessage('复制失败，请手动选择文字复制'); }
  };
  const startPurchase = (voucher: Voucher) => {
    if (mode === 'preview') return;
    if (!user) { setLoginOpen(true); return; }
    setOrderError(''); setOrderId(null); setCopyNotice(''); setSelected(voucher);
  };
  const placeOrder = async () => {
    if (busy || !selected || mode === 'preview') return;
    if (!user) { setSelected(null); setLoginOpen(true); return; }
    if (voucherState(selected, Date.now()) !== '立即抢购') { setOrderError('当前优惠券不可抢购，请刷新后查看最新状态。'); return; }
    setBusy(true); setOrderError('');
    try { const result = await seckill(selected.id); setOrderId(result); vouchers.reload(); }
    catch (error) { setOrderError(errorMessage(error)); }
    finally { setBusy(false); }
  };
  if (shop.loading) return <div className="container detail-page"><div className="detail-loading" role="status" aria-label="商家信息加载中"><div className="skeleton-image" /><div className="skeleton-content"><div /><div /><div /></div></div></div>;
  if (shop.error || !shop.data) return <div className="container detail-page"><Link className="text-link" to="/shops"><ArrowLeft size={17} />返回商家列表</Link><ErrorState message={shop.error || '商家信息不存在'} retry={shop.reload} preview={() => setMode('preview')} /></div>;
  const s = shop.data;
  return <div className="container detail-page">
    <div className="breadcrumb"><Link to="/">首页</Link><CaretRight size={12} /><Link to={`/shops?type=${s.typeId}`}>{category?.name || '商家'}</Link><CaretRight size={12} /><span>{s.name}</span></div>
    <section className="shop-overview"><div className="shop-gallery"><button className="gallery-main" onClick={() => setGalleryOpen(true)} aria-label={`放大查看${s.name}的照片`} disabled={!allImages.length}><Photo src={allImages[currentImage]} alt={`${s.name}的商家照片 ${currentImage + 1}`} eager /></button><div className="gallery-thumbnails">{allImages.slice(0, 5).map((src, index) => <button key={`${src}-${index}`} onClick={() => setActiveImage(index)} className={currentImage === index ? 'selected' : ''} aria-label={`查看商家照片 ${index + 1}`} aria-pressed={currentImage === index}><Photo src={src} alt={`${s.name}缩略图 ${index + 1}`} eager /></button>)}{allImages.length > 5 && <button className="gallery-more" onClick={() => setGalleryOpen(true)}>全部照片</button>}</div></div>
      <div className="shop-overview-info"><div className="shop-category-label"><Storefront size={16} />{category?.name || '本地商家'}<span>{s.area}</span></div><h1>{s.name}</h1><Rating score={s.score} comments={s.comments} /><div className="detail-price"><span>人均消费</span><strong>{s.avgPrice !== undefined ? `¥${s.avgPrice}` : '暂无信息'}</strong></div><div className="merchant-facts"><div><MapPin size={20} /><span><small>商家地址</small>{s.address || '商家暂未提供地址'}</span><button className="icon-button" title="复制地址" aria-label="复制商家地址" onClick={() => copy(s.address, setAddressNotice)}><Copy size={17} /></button></div><div><Clock size={20} /><span><small>营业时间</small>{s.openHours || '商家暂未提供营业时间'}</span></div></div>{addressNotice && <span className="copy-notice" role="status">{addressNotice}</span>}<a className="button primary detail-cta" href="#vouchers"><Ticket size={19} />查看到店优惠<ArrowRight size={18} /></a><p className="detail-helper">出发前，记得确认营业时间与优惠券使用规则。</p></div>
    </section>
    <div className="detail-body-layout"><div className="detail-primary"><nav className="detail-tabs" aria-label="商家详情导航"><Link to="#vouchers" className={!hash || hash === '#vouchers' ? 'active' : ''}>到店优惠</Link><Link to="#merchant-info" className={hash === '#merchant-info' ? 'active' : ''}>商家信息</Link><Link to="#reviews" className={hash === '#reviews' ? 'active' : ''}>评分评价</Link></nav>
      <section id="vouchers" className="detail-section"><div className="section-heading"><div><h2><Ticket size={24} />到店优惠</h2><p>看好使用规则，再把喜欢的优惠带走。</p></div></div>
        {vouchers.loading ? <div className="voucher-loading" role="status">正在加载商家优惠…</div> : vouchers.error ? <ErrorState message={vouchers.error} retry={vouchers.reload} /> : !vouchers.data?.length ? <Empty title="这家店暂未发布优惠券" description="先看看商家信息，更多优惠等你下次发现。" /> : <div className="voucher-list">{vouchers.data.map((voucher) => <VoucherCard key={voucher.id} voucher={voucher} now={now} buy={() => startPurchase(voucher)} preview={mode === 'preview'} />)}</div>}
      </section>
      <section id="merchant-info" className="detail-section merchant-info"><h2>商家信息</h2><dl><div><dt>商家名称</dt><dd>{s.name}</dd></div><div><dt>所属商圈</dt><dd>{s.area || '暂未提供'}</dd></div><div><dt>详细地址</dt><dd>{s.address || '暂未提供'}</dd></div><div><dt>营业时间</dt><dd>{s.openHours || '暂未提供'}</dd></div></dl></section>
      <section id="reviews" className="detail-section"><h2>评分评价</h2><div className="review-summary"><span className="review-score">{s.score !== undefined ? (s.score / 10).toFixed(1) : '暂无'}<small> / 5</small></span><div><Rating score={s.score} /><p>{s.comments !== undefined ? `来自 ${s.comments.toLocaleString('zh-CN')} 条商家评价` : '商家暂未提供评价统计'}</p></div></div><p className="review-note"><Info size={17} />当前仅提供评分与评价数量，详细评价暂未开放。</p></section>
    </div><aside className="detail-aside"><div className="visit-card"><span className="aside-icon"><Storefront size={28} /></span><h2>把这家店，<br />放进下一次计划。</h2><p>逛一逛相册，看看商圈，<br />找一个刚好的时间出发。</p><div className="aside-divider" /><div className="visit-fact"><MapPin size={18} /><span>{s.area || '本地商家'}</span></div><div className="visit-fact"><Clock size={18} /><span>{s.openHours || '营业时间待确认'}</span></div><Link to={`/shops?type=${s.typeId}`} className="button secondary full-width">继续逛逛同类商家<ArrowRight size={16} /></Link></div><Link className="write-shop-link text-link" to={`/stories/new?shop=${s.id}`}>为这家店写笔记<ArrowRight size={16} /></Link><div className="shopping-tips"><Ticket size={20} /><strong>到店小贴士</strong><p>代金券的适用时段、使用范围与叠加规则，以商家发布的说明为准。</p></div></aside></div>
    <Modal open={galleryOpen} onOpenChange={setGalleryOpen} title={`${s.name} · 商家相册`} description={`共 ${allImages.length} 张照片，当前第 ${currentImage + 1} 张。`} wide><div className="lightbox-photo"><Photo src={allImages[currentImage]} alt={`${s.name}的商家照片 ${currentImage + 1}`} eager /></div><div className="lightbox-controls"><button className="button secondary" onClick={() => setActiveImage((currentImage - 1 + allImages.length) % allImages.length)} disabled={allImages.length < 2}><CaretLeft size={17} />上一张</button><span>{currentImage + 1} / {allImages.length}</span><button className="button secondary" onClick={() => setActiveImage((currentImage + 1) % allImages.length)} disabled={allImages.length < 2}>下一张<CaretRight size={17} /></button></div></Modal>
    <Modal open={!!selected} onOpenChange={(open) => { if (!open && !busy) setSelected(null); }} title={orderId ? '抢购请求已受理' : '确认抢购'} description={orderId ? '订单正在异步处理，请保留返回编号。' : '确认商家与优惠券信息后，再提交抢购请求。'}>{orderId ? <div className="order-result"><CheckCircle size={42} weight="duotone" /><p>返回编号</p><strong className="order-id">{String(orderId)}</strong><button className="button secondary full-width" onClick={() => copy(String(orderId), setCopyNotice)}><Copy size={17} />复制编号</button>{copyNotice && <p role="status">{copyNotice}</p>}<p className="muted">此结果表示请求已受理，不代表支付完成。</p></div> : selected && <div className="confirm-order"><strong>{s.name}</strong><p>{selected.title}</p><div className="confirm-price"><span>抢购价格</span><strong>¥{money(selected.payValue)}</strong></div><p className="muted">{selected.subTitle}</p>{orderError && <p role="alert" className="form-error">{orderError}</p>}<button className="button primary full-width" onClick={placeOrder} disabled={busy}>{busy ? '正在提交，请稍候' : '确认抢购'}</button></div>}</Modal>
  </div>;
}
