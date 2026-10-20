import { useCallback, useEffect } from 'react';
import { ArrowLeft, ArrowRight, CaretRight, Heart } from '@phosphor-icons/react';
import { Link, useSearchParams } from 'react-router-dom';
import { useApp } from '../app-context';
import { useResource } from '../hooks/use-resource';
import { BlogCard, EmptyNotes, WritePrompt } from '../components/community/index';
import { ErrorState, Loading } from '../components/ui';

export default function Stories() {
  const { catalog, setMode } = useApp();
  const [params, setParams] = useSearchParams();
  const raw = Number(params.get('page'));
  const current = Number.isSafeInteger(raw) && raw > 0 ? raw : 1;
  const blogs = useResource(useCallback((signal) => catalog.blogs(signal, current), [catalog, current]));
  useEffect(() => { document.title = '探店笔记 · 记录值得分享的生活 - 黑马点评'; }, []);
  const changePage = (value: number) => { setParams({ page: String(value) }); window.scrollTo(0, 200); };
  return <div className="container community-page">
    <div className="breadcrumb"><Link to="/">首页</Link><CaretRight size={12} /><span>探店笔记</span></div>
    <section className="journal-intro"><div><span className="journal-intro-label">去过，喜欢，分享。</span><h1>把值得去的地方，分享出来。</h1><p>一顿好饭，一次相聚。跟着大家的记录，发现生活里的好去处。</p></div><span className="journal-intro-mark" aria-hidden="true"><Heart size={62} weight="duotone" /></span></section>
    <div className="community-layout"><section aria-label="热门探店笔记"><div className="results-toolbar"><span><strong>热门笔记</strong><span className="muted">第 {current} 页</span></span><span className="sort-description">按点赞数排列</span></div>
      {blogs.loading ? <Loading count={3} /> : blogs.error ? <ErrorState message={blogs.error} retry={blogs.reload} preview={() => setMode('preview')} /> : !blogs.data?.length ? <EmptyNotes /> : <div className="journal-grid">{blogs.data.map((blog) => <BlogCard blog={blog} key={blog.id} />)}</div>}
      <nav className="pagination" aria-label="探店笔记分页"><button className="button secondary" disabled={current === 1 || blogs.loading} onClick={() => changePage(current - 1)}><ArrowLeft size={16} />上一页</button><span aria-current="page">{current}</span><button className="button secondary" disabled={blogs.loading || !!blogs.error || (blogs.data?.length || 0) < 10} onClick={() => changePage(current + 1)}>下一页<ArrowRight size={16} /></button></nav>
    </section><aside className="community-aside"><WritePrompt /><div className="quiet-aside"><strong>下一站，从一篇笔记开始</strong><p>看看菜品与环境，听听作者的体验，再决定你的下一次出发。</p><Link className="text-link" to="/shops">也可以直接找商家<ArrowRight size={16} /></Link></div></aside></div>
  </div>;
}
