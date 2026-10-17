import { useCallback, useEffect, useRef, useState, type ChangeEvent, type FormEvent } from 'react';
import { CaretRight, Check, Eye, FloppyDisk, ImageSquare, MagnifyingGlass, MapPin, PaperPlaneTilt, Plus, Trash } from '@phosphor-icons/react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { AuthGate } from '../components/community';
import { Empty, ErrorState, Modal, Photo } from '../components/ui';
import { useApp } from '../context';
import { useResource } from '../lib/use-resource';
import { errorMessage, imagesOf, textToHtml } from '../lib/utils';
import type { Shop } from '../types';

interface Draft { title: string; content: string; shop: Shop | null; images: string[] }
const emptyDraft: Draft = { title: '', content: '', shop: null, images: [] };
function readDraft(key: string): Draft {
  try {
    const value = JSON.parse(localStorage.getItem(key) || 'null');
    if (!value || typeof value.title !== 'string' || typeof value.content !== 'string') return emptyDraft;
    return { title: value.title.slice(0, 80), content: value.content.slice(0, 1500), shop: value.shop?.id && typeof value.shop.name === 'string' ? value.shop : null, images: Array.isArray(value.images) ? value.images.filter((src: unknown) => typeof src === 'string' && !src.startsWith('blob:')).slice(0, 9) : [] };
  } catch { return emptyDraft; }
}

export default function StoryEditor() {
  const { mode, user } = useApp();
  return <AuthGate title="登录后，分享你的探店体验"><Editor key={`${mode}:${user?.id || 'guest'}`} /></AuthGate>;
}

function Editor() {
  const { catalog, community, mode, user } = useApp();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const initialShopId = params.get('shop');
  const key = `hmdp-draft:${mode}:${user?.id || 'preview'}`;
  const [draft, setDraft] = useState(() => readDraft(key));
  const latestDraft = useRef(draft);
  latestDraft.current = draft;
  const [notice, setNotice] = useState('');
  const [error, setError] = useState('');
  const [uploading, setUploading] = useState(false);
  const [deleting, setDeleting] = useState('');
  const [busy, setBusy] = useState(false);
  const [chooser, setChooser] = useState(false);
  const [previewOpen, setPreviewOpen] = useState(false);
  const [search, setSearch] = useState('');
  const [query, setQuery] = useState('');
  const [page, setPage] = useState(1);
  const fileInput = useRef<HTMLInputElement>(null);
  const uploadController = useRef<AbortController | null>(null);
  const uploadLock = useRef(false);
  const alive = useRef(true);
  const localUrls = useRef<string[]>([]);
  const published = useRef(false);
  const shops = useResource(useCallback((signal) => chooser ? catalog.shops({ name: query, current: page }, signal) : Promise.resolve([]), [catalog, chooser, query, page]));
  const initialShop = useResource(useCallback((signal) => initialShopId ? catalog.shop(initialShopId, signal) : Promise.resolve(null), [catalog, initialShopId]));
  useEffect(() => { if (initialShop.data) setDraft((value) => value.shop ? value : { ...value, shop: initialShop.data! }); }, [initialShop.data]);
  useEffect(() => {
    document.title = '发布探店笔记 - 黑马点评'; alive.current = true;
    return () => {
      alive.current = false;
      if (!published.current) {
        const value = latestDraft.current;
        if (value.title || value.content || value.shop || value.images.length) {
          try { localStorage.setItem(key, JSON.stringify({ ...value, images: mode === 'preview' ? [] : value.images })); } catch { /* The live form remains the source of truth if storage is unavailable. */ }
        }
      }
      uploadController.current?.abort();
      localUrls.current.forEach((url) => URL.revokeObjectURL(url));
    };
  }, []);
  const save = useCallback(() => {
    try {
      localStorage.setItem(key, JSON.stringify({ ...draft, images: mode === 'preview' ? [] : draft.images }));
      setNotice('草稿已保存在本机');
    } catch { setNotice('本机存储不可用，请暂时保留当前页面'); }
  }, [draft, key, mode]);
  useEffect(() => {
    if (!draft.title && !draft.content && !draft.shop && !draft.images.length) return;
    const timer = setTimeout(() => { if (!published.current) save(); }, 700);
    return () => clearTimeout(timer);
  }, [save, draft]);
  const addImages = async (event: ChangeEvent<HTMLInputElement>) => {
    const files = Array.from(event.target.files || []); event.target.value = '';
    if (!files.length || uploadLock.current) return;
    setError('');
    if (draft.images.length + files.length > 9) { setError('最多添加9张图片，请减少选择数量。'); return; }
    if (files.some((file) => !['image/jpeg', 'image/png', 'image/webp'].includes(file.type))) { setError('请选择 JPG、PNG 或 WebP 图片。'); return; }
    if (files.some((file) => file.size > 10 * 1024 * 1024)) { setError('单张图片请控制在10MB以内。'); return; }
    if (mode === 'preview') {
      const urls = files.map((file) => URL.createObjectURL(file)); localUrls.current.push(...urls);
      setDraft((value) => ({ ...value, images: [...value.images, ...urls] })); return;
    }
    uploadLock.current = true; setUploading(true);
    const controller = new AbortController(); uploadController.current = controller;
    try {
      for (const file of files) {
        const url = await community.upload(file, controller.signal);
        if (alive.current) setDraft((value) => ({ ...value, images: [...value.images, url] }));
      }
    } catch (error) { if (alive.current) setError(errorMessage(error)); }
    finally { uploadLock.current = false; if (alive.current) setUploading(false); }
  };
  const remove = async (url: string) => {
    setDeleting(url); setError('');
    try {
      if (mode === 'live') await community.deleteUpload(url);
      else URL.revokeObjectURL(url);
      setDraft((value) => ({ ...value, images: value.images.filter((src) => src !== url) }));
    } catch (error) { setError(errorMessage(error)); }
    finally { setDeleting(''); }
  };
  const submit = async (event: FormEvent) => {
    event.preventDefault(); setError('');
    if (mode === 'preview' || busy || uploading || deleting) return;
    if (!draft.title.trim()) { setError('请为笔记写一个标题。'); return; }
    if (!draft.content.trim()) { setError('写一点你的实际体验，再分享给大家。'); return; }
    if (!draft.shop) { setError('请选择这篇笔记关联的商家。'); return; }
    if (!draft.images.length) { setError('请至少添加一张探店照片。'); return; }
    const content = textToHtml(draft.content.trim());
    if ([...content].length > 2048) { setError('正文较长，请适当精简后再发布。'); return; }
    if (draft.images.join(',').length > 2048) { setError('当前图片组合超出发布限制，请减少图片数量。'); return; }
    save();
    setBusy(true);
    try {
      const id = await community.publish({ title: draft.title.trim(), content, images: draft.images.join(','), shopId: draft.shop.id });
      published.current = true;
      try { localStorage.removeItem(key); } catch { /* Publication already succeeded; storage is optional. */ }
      if (alive.current) navigate(`/stories/${id}`);
    } catch (error) { setError(errorMessage(error)); setBusy(false); }
  };
  const choose = (shop: Shop) => { setDraft((value) => ({ ...value, shop })); setChooser(false); };
  return <div className="container community-page editor-page"><div className="breadcrumb"><Link to="/">首页</Link><CaretRight size={12} /><Link to="/stories">探店笔记</Link><CaretRight size={12} /><span>发布笔记</span></div><div className="page-heading editor-heading"><div><h1>记录一次，值得分享的体验。</h1><p>把你的发现，变成别人下一次出发的灵感。</p></div><span className="draft-status" role="status"><FloppyDisk size={15} />{notice || '文字与关联商家自动保存本机草稿'}</span></div>
    <form className="editor-layout" onSubmit={submit} noValidate><div className="editor-main"><section className="editor-block"><div className="field-heading"><h2>探店照片</h2><span>{draft.images.length} / 9 张</span></div><p className="field-help">第一张将作为笔记封面。支持 JPG、PNG、WebP，单张不超过10MB。</p><div className="upload-grid">{draft.images.map((src, index) => <div className="uploaded-photo" key={src}><Photo src={src} alt={`待发布的探店照片 ${index + 1}`} /><button type="button" className="image-remove" aria-label={`移除图片 ${index + 1}`} onClick={() => remove(src)} disabled={!!deleting || uploading || busy}><Trash size={17} /></button><small>{index === 0 ? '封面' : `照片 ${index + 1}`}</small></div>)}{draft.images.length < 9 && <button type="button" className="upload-button" onClick={() => fileInput.current?.click()} disabled={uploading || busy || !!deleting}><Plus size={29} /><strong>{uploading ? '正在上传' : '添加照片'}</strong><span>{mode === 'preview' ? '本地预览' : '最多9张'}</span></button>}</div><label htmlFor="note-images" className="sr-only">添加探店图片</label><input hidden ref={fileInput} id="note-images" type="file" multiple accept="image/jpeg,image/png,image/webp" onChange={addImages} disabled={uploading || busy} />{mode === 'preview' && <p className="field-help">预览图片仅在当前页面有效，发布功能在连接后端后启用。</p>}</section>
      <section className="editor-block editor-fields"><label htmlFor="note-title">笔记标题<span>{draft.title.length} / 80</span></label><input id="note-title" maxLength={80} value={draft.title} onChange={(event) => setDraft({ ...draft, title: event.target.value })} placeholder="给这次发现起一个标题" disabled={busy} /><label htmlFor="note-content">探店体验<span>{draft.content.length} / 1500</span></label><textarea id="note-content" maxLength={1500} value={draft.content} onChange={(event) => setDraft({ ...draft, content: event.target.value })} placeholder="有什么让你印象深刻的菜品、环境或服务？写下你的真实体验。" disabled={busy} rows={12} /><p className="field-help">建议介绍菜品、环境、人均消费和适合的场景。</p></section>
      <section className="editor-block"><div className="field-heading"><h2>关联商家</h2><button className="text-link" type="button" onClick={() => setChooser(true)} disabled={busy}>{draft.shop ? '更换商家' : '选择商家'}<CaretRight size={15} /></button></div>{draft.shop ? <div className="selected-shop"><Photo src={imagesOf(draft.shop.images)[0]} alt={draft.shop.name} thumbnail /><div><strong>{draft.shop.name}</strong><p><MapPin size={14} />{draft.shop.area} {draft.shop.address}</p></div><span className="selected-shop-check"><Check size={19} /></span></div> : <button type="button" className="select-shop-empty" onClick={() => setChooser(true)}><MapPin size={22} /><span>这篇笔记，发生在哪家店？</span><Plus size={19} /></button>}</section>
      {error && <p className="form-error editor-error" role="alert">{error}</p>}<div className="editor-actions"><button type="button" className="button secondary" onClick={save} disabled={busy}><FloppyDisk size={17} />保存草稿</button><button type="button" className="button secondary" onClick={() => setPreviewOpen(true)}><Eye size={18} />预览笔记</button><button type="submit" className="button primary" disabled={mode === 'preview' || busy || uploading || !!deleting}><PaperPlaneTilt size={18} />{busy ? '正在发布' : mode === 'preview' ? '预览模式不可发布' : '发布笔记'}</button></div>
    </div><aside className="editor-aside"><div className="editor-guide"><span className="aside-icon"><ImageSquare size={28} /></span><h2>你的真实体验，<br />就是最好的推荐。</h2><p>不必写得面面俱到。<br />把最想分享的感受留下来。</p><div className="aside-divider" /><ul className="publish-checklist"><li><Check size={17} />清晰的探店照片</li><li><Check size={17} />简洁、有内容的标题</li><li><Check size={17} />具体的到店体验</li><li><Check size={17} />正确的关联商家</li></ul></div><p className="editor-local-note"><FloppyDisk size={16} />草稿仅保存在当前浏览器中。发布成功后会自动清除。</p></aside></form>
    <Modal open={chooser} onOpenChange={setChooser} title="选择关联商家" description="按商家名称搜索，选择这次体验发生的店铺。" wide><form className="shop-picker-search" onSubmit={(event) => { event.preventDefault(); setQuery(search.trim()); setPage(1); }}><label className="sr-only" htmlFor="pick-shop">搜索关联商家</label><MagnifyingGlass size={20} /><input id="pick-shop" value={search} onChange={(event) => setSearch(event.target.value)} placeholder="输入商家名称" /><button className="button primary" type="submit">查找商家</button></form><div className="shop-picker-results">{shops.loading ? <p role="status">正在查找商家…</p> : shops.error ? <ErrorState message={shops.error} retry={shops.reload} /> : !shops.data?.length ? <Empty title="没有找到这家店" description="换一个商家名称试试。" /> : shops.data.map((shop) => <button className="shop-picker-option" type="button" key={shop.id} onClick={() => choose(shop)}><Photo src={imagesOf(shop.images)[0]} alt={shop.name} thumbnail /><span><strong>{shop.name}</strong><small>{shop.area} {shop.address}</small></span><Check size={19} /></button>)}</div><div className="shop-picker-pagination"><button type="button" className="button secondary" disabled={page === 1 || shops.loading} onClick={() => setPage(page - 1)}>上一页</button><span>第 {page} 页</span><button type="button" className="button secondary" disabled={shops.loading || !!shops.error || (shops.data?.length || 0) < 10} onClick={() => setPage(page + 1)}>下一页</button></div></Modal>
    <Modal open={previewOpen} onOpenChange={setPreviewOpen} title={draft.title || '笔记预览'} description={draft.shop?.name || '尚未选择关联商家'} wide>{draft.images.length ? <div className="post-gallery">{draft.images.map((src, index) => <Photo key={src} src={src} alt={`预览照片 ${index + 1}`} />)}</div> : <div className="editor-preview-empty"><ImageSquare size={36} /><p>添加照片后，这里将展示探店相册。</p></div>}<p className="post-body">{draft.content || '写一点到店体验，让你的发现更完整。'}</p></Modal>
  </div>;
}
