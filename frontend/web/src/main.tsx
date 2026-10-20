import { StrictMode, lazy, useEffect } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter, Link, Route, Routes } from 'react-router-dom';
import { IconContext } from '@phosphor-icons/react';
import { AppProvider } from './app-context';
import { Layout } from './components/layout';
import { Empty } from './components/ui';
import Home from './pages/home';
import './styles.css';
import './community.css';

const Shops = lazy(() => import('./pages/shops'));
const ShopDetail = lazy(() => import('./pages/shop-detail'));
const Stories = lazy(() => import('./pages/stories'));
const StoryDetail = lazy(() => import('./pages/story-detail'));
const StoryEditor = lazy(() => import('./pages/story-editor'));
const Profile = lazy(() => import('./pages/profile'));
function HomePage() { useEffect(() => { document.title = '黑马点评 · 发现生活的好去处'; }, []); return <Home />; }

createRoot(document.getElementById('root')!).render(
  <StrictMode><IconContext.Provider value={{ weight: 'regular', size: 20 }}><BrowserRouter><AppProvider><Routes><Route element={<Layout />}>
    <Route index element={<HomePage />} />
    <Route path="shops" element={<Shops />} />
    <Route path="shops/:id" element={<ShopDetail />} />
    <Route path="stories" element={<Stories />} />
    <Route path="stories/new" element={<StoryEditor />} />
    <Route path="stories/:id" element={<StoryDetail />} />
    <Route path="account" element={<Profile />} />
    <Route path="people/:id" element={<Profile />} />
    <Route path="*" element={<div className="container"><Empty title="这个页面暂时找不到了" description="回到首页，继续发现好生活。"><Link className="button primary" to="/">返回首页</Link></Empty></div>} />
  </Route></Routes></AppProvider></BrowserRouter></IconContext.Provider></StrictMode>,
);
