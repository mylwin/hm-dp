import { isBackendApi } from './helpers/api-route';
import { test, expect, type Page } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import preview from '../src/mocks/preview-data.json' with { type: 'json' };

async function mockApi(page: Page, options: { expired?: boolean; fail?: boolean; empty?: boolean } = {}) {
  const calls: { path: string; method: string; token?: string; query: string }[] = [];
  await page.route(isBackendApi, async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname.replace('/api', '');
    calls.push({ path, method: request.method(), token: request.headers().authorization, query: url.search });
    if (options.fail) return route.fulfill({ status: 503, body: 'Unavailable' });
    if (options.expired && path === '/voucher-order/reliable/seckill/99') return route.fulfill({ status: 401, body: '' });
    let data: unknown = null;
    if (path === '/shop-type/list') data = preview.types;
    else if (path === '/user/me') data = { id: 1, nickName: '接口验证用户' };
    else if (path === '/shop/1') data = preview.shops[0];
    else if (path.startsWith('/shop/of/')) data = options.empty ? [] : preview.shops.slice(0, 5);
    else if (path === '/blog/hot') data = preview.blogs;
    else if (path === '/voucher/list/1') data = [{ ...preview.vouchers[0], id: 99, type: 1, stock: 3, beginTime: '2020-01-01T00:00:00', endTime: '2099-01-01T00:00:00' }, { ...preview.vouchers[0], id: 100, type: 1, stock: 0 }, { ...preview.vouchers[0], id: 101, type: 1, endTime: '2020-01-01T00:00:00' }, { ...preview.vouchers[0], id: 102, type: 1, beginTime: '2099-01-01T00:00:00' }];
    else if (path === '/voucher-order/reliable/seckill/99') {
      await new Promise((resolve) => setTimeout(resolve, 120));
      const { requestId } = request.postDataJSON() as { requestId: string };
      data = { orderId: '856725678012345678', requestId, voucherId: '99', status: 'SUCCEEDED', statusDescription: '建单成功' };
    }
    return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ success: true, data }) });
  });
  return calls;
}

test('预览中可浏览分类、翻页、搜索、查看相册和优惠规则', async ({ page }) => {
  const errors: string[] = [];
  page.on('pageerror', (error) => errors.push(error.message));
  await page.goto('/?preview=1');
  await expect(page.getByRole('heading', { level: 1 })).toContainText('好好吃饭');
  await expect(page.locator('.home-shops .shop-card')).toHaveCount(4);
  await page.locator('.hero-button').click();
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('发现美食好店');
  await expect(page.locator('.shop-list .shop-card')).toHaveCount(5);
  await page.getByRole('button', { name: '下一页' }).click();
  await expect(page).toHaveURL(/page=2/);
  await expect(page.locator('.pagination [aria-current]')).toHaveText('2');
  await page.getByRole('button', { name: '网格视图' }).click();
  await expect(page.locator('.listing-grid')).toBeVisible();
  await page.getByLabel('搜索商家', { exact: true }).fill('103');
  await page.getByRole('button', { name: '搜索', exact: true }).click();
  await expect(page.locator('.shop-card')).toHaveCount(1);
  await page.locator('.shop-card').click();
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('103茶餐厅');
  await page.getByRole('button', { name: '放大查看103茶餐厅的照片' }).click();
  await expect(page.getByRole('dialog')).toBeVisible();
  await page.getByRole('button', { name: '下一张' }).click();
  await expect(page.locator('.lightbox-controls > span')).toHaveText('2 / 3');
  await page.keyboard.press('Escape');
  await expect(page.getByRole('dialog')).not.toBeVisible();
  await page.getByRole('button', { name: '使用规则' }).click();
  await expect(page.locator('.voucher-rules')).toContainText('全场通用');
  await page.getByRole('button', { name: '登录 / 注册', exact: true }).click();
  await expect(page.getByLabel('手机号', { exact: true })).toBeDisabled();
  await page.keyboard.press('Escape');
  expect(errors).toEqual([]);
});

test('空分类有明确空状态，并可返回全部商家', async ({ page }) => {
  await page.goto('/shops?type=4&preview=1');
  await expect(page.getByText('暂时没有相关商家')).toBeVisible();
  await page.getByRole('button', { name: '查看全部商家' }).click();
  await expect(page.locator('.shop-card')).toHaveCount(10);
});

test('三个页面在四种宽度和双主题下无横向溢出，关键区域可见', async ({ page }) => {
  for (const theme of ['light', 'dark']) {
    await page.addInitScript((value) => localStorage.setItem('hmdp-theme', value), theme);
    for (const width of [375, 768, 1024, 1440]) {
      await page.setViewportSize({ width, height: 1000 });
      for (const path of ['/', '/shops?type=1', '/shops/1']) {
        await page.goto(`${path}${path.includes('?') ? '&' : '?'}preview=1`);
        await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
        await page.waitForLoadState('networkidle');
        expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();
        if (width === 1440) {
          await page.locator('img').evaluateAll((images) => images.forEach((image) => { image.loading = 'eager'; }));
          await page.waitForFunction(() => [...document.images].every((image) => image.complete));
          const name = path === '/' ? 'home' : path.includes('?') ? 'shops' : 'detail';
          await page.screenshot({ path: `test-results/screenshots/${name}-${theme}.png`, fullPage: true });
        }
      }
    }
  }
});

test('双主题的三个页面通过 WCAG AA 自动检查', async ({ page }) => {
  for (const theme of ['light', 'dark']) {
    await page.addInitScript((value) => localStorage.setItem('hmdp-theme', value), theme);
    for (const path of ['/', '/shops?type=1', '/shops/1']) {
      await page.goto(`${path}${path.includes('?') ? '&' : '?'}preview=1`);
      await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
      const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze();
      expect(results.violations.map((v) => ({ id: v.id, nodes: v.nodes.map((n) => n.target) }))).toEqual([]);
    }
  }
});

test('实时接口参数不变，分类与关键词使用正确接口', async ({ page }) => {
  const calls = await mockApi(page);
  await page.goto('/shops?type=1&preview=0');
  await expect(page.locator('.shop-card')).toHaveCount(5);
  expect(calls.some((call) => call.path === '/shop/of/type' && call.query.includes('typeId=1') && call.query.includes('current=1'))).toBeTruthy();
  await page.getByRole('button', { name: '下一页' }).click();
  await expect.poll(() => calls.some((call) => call.path === '/shop/of/type' && call.query.includes('current=2'))).toBeTruthy();
  await page.getByLabel('搜索商家', { exact: true }).fill('茶餐厅');
  await page.getByRole('button', { name: '搜索', exact: true }).click();
  await expect.poll(() => calls.some((call) => call.path === '/shop/of/name' && new URLSearchParams(call.query).get('name') === '茶餐厅')).toBeTruthy();
  expect(calls.every((call) => !call.query.includes('sortBy'))).toBeTruthy();
});

test('秒杀仅提交一次，保留完整长订单编号，并区分库存和时间状态', async ({ page }) => {
  await page.addInitScript(() => sessionStorage.setItem('token', 'contract-test-token'));
  const calls = await mockApi(page);
  await page.goto('/shops/1?preview=0');
  await expect(page.getByRole('button', { name: '已抢光', exact: true })).toBeDisabled();
  await expect(page.getByRole('button', { name: '已结束', exact: true })).toBeDisabled();
  await expect(page.getByRole('button', { name: '未开始', exact: true })).toBeDisabled();
  await expect(page.locator('.account-button')).toContainText('接口验证用户');
  await page.getByRole('button', { name: '立即抢购', exact: true }).click();
  await page.getByRole('button', { name: '确认抢购', exact: true }).click();
  await expect(page.getByRole('button', { name: '正在提交，请稍候' })).toBeDisabled();
  await expect(page.getByRole('heading', { name: '订单创建成功', exact: true })).toBeVisible();
  await expect(page.locator('.order-id')).toHaveText('856725678012345678');
  expect(calls.filter((call) => call.path === '/voucher-order/reliable/seckill/99')).toEqual([{ path: '/voucher-order/reliable/seckill/99', method: 'POST', token: 'contract-test-token', query: '' }]);
});

test('后端错误显示重试入口；401 清理令牌并引导登录', async ({ page }) => {
  await mockApi(page, { fail: true });
  await page.goto('/shops?preview=0');
  await expect(page.getByRole('heading', { name: '内容暂时无法加载' })).toBeVisible();
  await expect(page.getByRole('button', { name: '重新加载' })).toBeVisible();
  await page.unroute(isBackendApi);
  await page.addInitScript(() => sessionStorage.setItem('token', 'expired-test-token'));
  await mockApi(page, { expired: true });
  await page.goto('/shops/1?preview=0');
  await expect(page.locator('.account-button')).toContainText('接口验证用户');
  await page.getByRole('button', { name: '立即抢购', exact: true }).click();
  await page.getByRole('button', { name: '确认抢购', exact: true }).click();
  await expect(page.getByLabel('手机号', { exact: true })).toBeVisible();
  expect(await page.evaluate(() => sessionStorage.getItem('token'))).toBeNull();
});
