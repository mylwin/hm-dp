import { expect, test, type Page } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import snapshot from '../src/preview-data.json' with { type: 'json' };

const image = { name: 'test-photo.png', mimeType: 'image/png', buffer: Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+yAYsAAAAASUVORK5CYII=', 'base64') };

test('登录弹窗沿用验证码、登录字段和原始令牌请求头', async ({ page }) => {
  const calls: { path: string; method: string; query: string; token?: string; body: string | null }[] = [];
  await page.route('**/api/**', (route) => {
    const request = route.request(), url = new URL(request.url()), path = url.pathname.replace('/api', '');
    calls.push({ path, method: request.method(), query: url.search, token: request.headers().authorization, body: request.postData() });
    const data = path === '/user/login' ? 'login-contract-token'
      : path === '/user/me' ? { id: 99, nickName: '登录验证用户' }
      : path === '/shop-type/list' ? snapshot.types : path === '/user/code' ? null : [];
    return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ success: true, data }) });
  });
  await page.goto('/?preview=0');
  await page.locator('.account-button').click();
  const dialog = page.getByRole('dialog');
  await dialog.getByLabel('手机号', { exact: true }).fill('13800138000');
  await dialog.getByRole('button', { name: '获取验证码', exact: true }).click();
  await expect(dialog.getByRole('button', { name: /秒后重试/ })).toBeDisabled();
  await dialog.getByLabel('验证码', { exact: true }).fill('123456');
  await dialog.getByRole('button', { name: '登录 / 注册', exact: true }).click();
  await expect(dialog).not.toBeVisible();
  await expect(page.locator('.account-button')).toContainText('登录验证用户');
  expect(calls.some((call) => call.path === '/user/code' && call.method === 'POST' && new URLSearchParams(call.query).get('phone') === '13800138000')).toBeTruthy();
  const login = calls.find((call) => call.path === '/user/login');
  expect(login?.method).toBe('POST');
  expect(JSON.parse(login?.body || '{}')).toEqual({ phone: '13800138000', code: '123456' });
  expect(calls.some((call) => call.path === '/user/me' && call.token === 'login-contract-token')).toBeTruthy();
  expect(await page.evaluate(() => sessionStorage.getItem('token'))).toBe('login-contract-token');
});

async function communityApi(page: Page) {
  const calls: { path: string; method: string; query: string; token?: string; body?: string; contentType?: string }[] = [];
  let liked = false, following = false, signed = false;
  let published: Record<string, unknown> | null = null;
  await page.addInitScript(() => sessionStorage.setItem('token', 'community-contract-token'));
  await page.route('**/api/**', async (route) => {
    const req = route.request(), url = new URL(req.url()), path = url.pathname.replace('/api', '');
    calls.push({ path, method: req.method(), query: url.search, token: req.headers().authorization, body: req.postData() || undefined, contentType: req.headers()['content-type'] });
    let data: unknown = null;
    if (path === '/user/me') data = { id: 99, nickName: '社区验证用户' };
    else if (/^\/user\/info\//.test(path)) data = { userId: 99, city: '杭州', introduce: '用于测试的公开简介', fans: 0, followee: 3 };
    else if (/^\/user\/\d+$/.test(path)) data = { id: 2, nickName: '作者验证用户' };
    else if (path === '/user/sign/count') data = signed ? 1 : 0;
    else if (path === '/user/sign') { signed = true; data = null; }
    else if (path === '/shop-type/list') data = snapshot.types;
    else if (path.startsWith('/shop/of/')) data = snapshot.shops.slice(0, 5);
    else if (/^\/shop\/\d+$/.test(path)) data = snapshot.shops.find((shop) => String(shop.id) === path.split('/').pop());
    else if (path === '/blog/hot' || path === '/blog/of/user' || path === '/blog/of/me') data = snapshot.blogs;
    else if (path === '/blog/of/follow') {
      const offset = Number(url.searchParams.get('offset') || 0);
      data = offset < 6 ? { list: [0, 1, 2].map((_, i) => ({ ...snapshot.blogs[i], id: 40 + offset + i })), minTime: 1000, offset: 3 } : null;
    }
    else if (path === '/blog/4') data = { ...snapshot.blogs[0], liked: liked ? 8 : 7, isLike: liked };
    else if (path === '/blog/21') data = { ...snapshot.blogs[0], ...published, id: 21, name: '社区验证用户', userId: 99, liked: 0, isLike: false };
    else if (path === '/blog/likes/4') data = [];
    else if (path === '/blog/like/4') { await new Promise((resolve) => setTimeout(resolve, 180)); liked = !liked; }
    else if (path === '/follow/or/not/2') data = following;
    else if (path === '/follow/2/true') following = true;
    else if (path === '/follow/2/false') following = false;
    else if (path === '/follow/common/2') data = [];
    else if (path === '/upload/blog') data = '/images/lifestyle-1.webp';
    else if (path === '/blog' && req.method() === 'POST') { published = req.postDataJSON(); await new Promise((resolve) => setTimeout(resolve, 180)); data = 21; }
    return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ success: true, data }) });
  });
  return calls;
}

test('预览可编辑草稿、选择商家和预览，刷新后恢复文字且不发送写请求', async ({ page }) => {
  const requests: string[] = [];
  page.on('request', (request) => { if (request.url().includes('/api/')) requests.push(request.url()); });
  await page.goto('/stories/new?preview=1');
  await page.getByLabel('笔记标题').fill('周末在街角吃一顿好饭');
  await page.getByLabel('探店体验').fill('环境安静，适合朋友小聚。');
  await page.getByLabel('添加探店图片', { exact: true }).setInputFiles(image);
  await expect(page.locator('.uploaded-photo')).toHaveCount(1);
  await page.getByRole('button', { name: '选择商家', exact: true }).click();
  await page.locator('.shop-picker-option').first().click();
  await expect(page.locator('.selected-shop')).toContainText('103茶餐厅');
  await page.getByRole('button', { name: '保存草稿', exact: true }).click();
  await page.getByRole('button', { name: '预览笔记', exact: true }).click();
  await expect(page.getByRole('dialog')).toContainText('环境安静，适合朋友小聚。');
  await page.keyboard.press('Escape');
  await expect(page.getByRole('button', { name: '预览模式不可发布', exact: true })).toBeDisabled();
  await page.reload();
  await expect(page.getByLabel('笔记标题')).toHaveValue('周末在街角吃一顿好饭');
  await expect(page.locator('.selected-shop')).toContainText('103茶餐厅');
  await expect(page.locator('.uploaded-photo')).toHaveCount(0);
  expect(requests).toEqual([]);
});

test('发布使用正确 multipart、图片删除和博客字段，成功后清理草稿并进入详情', async ({ page }) => {
  const calls = await communityApi(page);
  await page.goto('/stories/new?preview=0');
  await page.getByLabel('笔记标题').fill('一次安静的午餐');
  await page.getByLabel('探店体验').fill('环境安静\n推荐 <招牌> & 茶点');
  await page.getByLabel('添加探店图片', { exact: true }).setInputFiles(image);
  await expect(page.locator('.uploaded-photo')).toHaveCount(1);
  const upload = calls.find((call) => call.path === '/upload/blog');
  expect(upload?.contentType).toMatch(/^multipart\/form-data; boundary=/);
  expect(upload?.body).toContain('name="file"');
  expect(upload?.token).toBe('community-contract-token');
  await page.getByRole('button', { name: '移除图片 1', exact: true }).click();
  await expect(page.locator('.uploaded-photo')).toHaveCount(0);
  expect(calls.some((call) => call.path === '/upload/blog/delete' && new URLSearchParams(call.query).get('name') === '/images/lifestyle-1.webp')).toBeTruthy();
  await page.getByLabel('添加探店图片', { exact: true }).setInputFiles(image);
  await expect(page.locator('.uploaded-photo')).toHaveCount(1);
  await page.getByRole('button', { name: '选择商家', exact: true }).click();
  await page.locator('.shop-picker-option').first().click();
  await page.getByRole('button', { name: '发布笔记', exact: true }).click();
  await expect(page.getByRole('button', { name: '正在发布', exact: true })).toBeDisabled();
  await expect(page).toHaveURL(/\/stories\/21$/);
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('一次安静的午餐');
  await expect(page.locator('.article-copy')).toContainText('推荐 <招牌> & 茶点');
  const publish = calls.filter((call) => call.path === '/blog' && call.method === 'POST');
  expect(publish).toHaveLength(1);
  expect(JSON.parse(publish[0].body!)).toEqual({ title: '一次安静的午餐', content: '环境安静<br>推荐 &lt;招牌&gt; &amp; 茶点', images: '/images/lifestyle-1.webp', shopId: 1 });
  expect(await page.evaluate(() => localStorage.getItem('hmdp-draft:live:99'))).toBeNull();
});

test('点赞与关注使用现有 toggle/布尔接口，并从后端读取最终状态', async ({ page }) => {
  const calls = await communityApi(page);
  await page.goto('/stories/4?preview=0');
  await page.getByRole('button', { name: '关注作者', exact: true }).click();
  await expect(page.getByRole('button', { name: '已关注', exact: true })).toBeVisible();
  await page.getByRole('button', { name: /^点赞\s*7$/ }).click();
  await expect(page.locator('.article-actions button').first()).toBeDisabled();
  await expect(page.getByRole('button', { name: /^已点赞\s*8$/ })).toHaveAttribute('aria-pressed', 'true');
  expect(calls.filter((call) => call.path === '/blog/like/4')).toHaveLength(1);
  expect(calls.some((call) => call.path === '/blog/like/4' && call.method === 'PUT')).toBeTruthy();
  expect(calls.some((call) => call.path === '/follow/2/true' && call.method === 'PUT')).toBeTruthy();
  await page.getByRole('button', { name: '已关注', exact: true }).click();
  await expect(page.getByRole('button', { name: '关注作者', exact: true })).toBeVisible();
  expect(calls.some((call) => call.path === '/follow/2/false')).toBeTruthy();
});

test('发布失败保留内容，快速离开编辑页也能恢复草稿', async ({ page }) => {
  await communityApi(page);
  await page.route('**/api/blog', (route) => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ success: false, errorMsg: '发布暂时失败，请稍后重试' }) }));
  await page.goto('/stories/new?preview=0');
  await page.getByLabel('笔记标题').fill('应当保留的草稿标题');
  await page.getByLabel('探店体验').fill('失败时仍应保留这段正文。');
  await page.getByLabel('添加探店图片', { exact: true }).setInputFiles(image);
  await expect(page.locator('.uploaded-photo')).toHaveCount(1);
  await page.getByRole('button', { name: '选择商家', exact: true }).click();
  await page.locator('.shop-picker-option').first().click();
  await page.getByRole('button', { name: '发布笔记', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('发布暂时失败');
  await expect(page.getByLabel('笔记标题')).toHaveValue('应当保留的草稿标题');
  await page.getByLabel('笔记标题').fill('最后一次编辑也要保存');
  await page.getByRole('navigation', { name: '主导航' }).getByRole('link', { name: '发现好店', exact: true }).click();
  await page.getByRole('navigation', { name: '主导航' }).getByRole('link', { name: '发布笔记', exact: true }).click();
  await expect(page.getByLabel('笔记标题')).toHaveValue('最后一次编辑也要保存');
  await expect(page.locator('.uploaded-photo')).toHaveCount(1);
});

test('关注动态保留同一时间戳的累计 offset，签到与资料、退出可用', async ({ page }) => {
  const calls = await communityApi(page);
  await page.goto('/account?tab=feed&preview=0');
  await expect(page.locator('.journal-card')).toHaveCount(3);
  await page.getByRole('button', { name: '查看更多动态', exact: true }).click();
  await expect(page.locator('.journal-card')).toHaveCount(6);
  await page.getByRole('button', { name: '查看更多动态', exact: true }).click();
  await expect(page.getByRole('button', { name: '查看更多动态', exact: true })).not.toBeVisible();
  const feed = calls.filter((call) => call.path === '/blog/of/follow' && new URLSearchParams(call.query).get('offset') !== '0');
  expect(new URLSearchParams(feed[0].query).get('offset')).toBe('3');
  expect(new URLSearchParams(feed[1].query).get('offset')).toBe('6');
  expect(new URLSearchParams(feed[1].query).get('lastId')).toBe('1000');
  await page.getByRole('button', { name: '每日签到', exact: true }).click();
  await expect(page.getByRole('button', { name: '今日已签到', exact: true })).toBeDisabled();
  await page.getByRole('button', { name: '个人资料', exact: true }).click();
  await expect(page.locator('.profile-info-panel')).toContainText('用于测试的公开简介');
  expect(calls.some((call) => call.path === '/user/sign' && call.method === 'PUT')).toBeTruthy();
  await page.getByRole('button', { name: '退出登录', exact: true }).click();
  await expect(page).toHaveURL('/');
  expect(await page.evaluate(() => sessionStorage.getItem('token'))).toBeNull();
});

test('未登录直接访问受保护页面时先显示登录入口，不请求受保护接口', async ({ page }) => {
  const calls: string[] = [];
  await page.route('**/api/**', (route) => { calls.push(route.request().url()); return route.fulfill({ status: 401, body: '' }); });
  for (const path of ['/stories/4', '/stories/new', '/account', '/people/2']) {
    await page.goto(`${path}?preview=0`);
    await expect(page.locator('.auth-gate h1')).toBeVisible();
  }
  expect(calls).toEqual([]);
});

test('新增页面在双主题及桌面、窄屏下无溢出，并通过 WCAG AA 检查', async ({ page }) => {
  test.setTimeout(90000);
  for (const theme of ['light', 'dark']) {
    await page.addInitScript((value) => localStorage.setItem('hmdp-theme', value), theme);
    for (const width of [375, 1440]) {
      await page.setViewportSize({ width, height: 1000 });
      for (const path of ['/stories', '/stories/4', '/stories/new', '/account', '/people/2']) {
        await page.goto(`${path}?preview=1`);
        await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
        expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();
        const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze();
        expect(results.violations.map((violation) => ({ id: violation.id, nodes: violation.nodes.map((node) => node.target) }))).toEqual([]);
      }
    }
  }
});
