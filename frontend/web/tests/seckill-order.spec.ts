import { test, expect, type Page } from '@playwright/test';
import { isBackendApi } from './helpers/api-route';
import preview from '../src/mocks/preview-data.json' with { type: 'json' };

// 默认界面只走新链路；独立配置可在单独端口运行本组契约测试。

type Scenario = { failSubmit?: boolean; failQuery?: boolean; stock?: number; rejected?: boolean; notFoundOnce?: boolean; userId?: number };
async function setup(page: Page, scenario: Scenario = {}) {
  const posts: string[] = [];
  let queries = 0;
  await page.addInitScript(() => sessionStorage.setItem('token', 'order-test-token'));
  await page.route(isBackendApi, async (route) => {
    const path = new URL(route.request().url()).pathname.replace('/api', '');
    let data: unknown = null;
    if (path === '/user/me') data = { id: scenario.userId || 1, nickName: '抢购测试用户' };
    else if (path === '/shop-type/list') data = preview.types;
    else if (path === '/shop/1') data = preview.shops[0];
    else if (path === '/voucher/list/1') data = [{ ...preview.vouchers[0], id: 99, type: 1,
      stock: scenario.stock ?? 3, beginTime: '2020-01-01T00:00:00', endTime: '2099-01-01T00:00:00' }];
    else if (path === '/voucher-order/reliable/seckill/99') {
      const { requestId } = route.request().postDataJSON() as { requestId: string };
      posts.push(requestId);
      if (scenario.failSubmit) return route.abort('timedout');
      await new Promise((resolve) => setTimeout(resolve, 100));
      data = result(requestId, scenario.rejected ? 'REJECTED' : posts.length > 1 ? 'SUCCEEDED' : 'ACCEPTED');
    } else if (path.startsWith('/voucher-order/reliable/requests/')) {
      queries++;
      if (scenario.failQuery) return route.fulfill({ status: 503, body: 'unavailable' });
      if (scenario.notFoundOnce && queries === 1) return route.fulfill({ status: 404, body: '{}' });
      data = result(decodeURIComponent(path.split('/').at(-1)!), queries > 1 ? 'SUCCEEDED' : 'ACCEPTED');
    } else if (path.startsWith('/voucher-order/seckill/')) {
      throw new Error('新链路不应调用旧秒杀入口');
    }
    return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ success: true, data }) });
  });
  return { posts, queries: () => queries };
}

function result(requestId: string, status: 'ACCEPTED' | 'SUCCEEDED' | 'REJECTED') {
  return { orderId: '856725678012345678', requestId, voucherId: '99', status,
    statusDescription: status === 'SUCCEEDED' ? '建单成功' : status === 'REJECTED' ? '已拒绝' : '已受理',
    reason: status === 'REJECTED' ? 'SOLD_OUT' : null,
    reasonDescription: status === 'REJECTED' ? '库存不足' : null };
}

async function openPurchase(page: Page) {
  await page.goto('/shops/1?preview=0');
  await expect(page.locator('.account-button')).toContainText('抢购测试用户');
  await page.getByRole('button', { name: '立即抢购', exact: true }).click();
  await page.getByRole('button', { name: '确认抢购', exact: true }).click();
}

test('提交一次并查询成功，长订单编号保持完整', async ({ page }) => {
  const api = await setup(page);
  await openPurchase(page);
  await expect(page.getByRole('heading', { name: '订单创建成功', exact: true })).toBeVisible();
  await expect(page.locator('.order-id')).toHaveText('856725678012345678');
  expect(api.posts).toHaveLength(1);
  await expect(page.locator('.request-id')).toHaveText(api.posts[0]);
  expect(api.queries()).toBeGreaterThan(0);
});

test('提交和查询网络异常后，手动重试仍使用原请求编号', async ({ page }) => {
  const scenario: Scenario = { failSubmit: true, failQuery: true };
  const api = await setup(page, scenario);
  await openPurchase(page);
  await expect(page.getByText('暂时无法确认结果，请稍后继续查询；原请求编号已保留。')).toBeVisible();
  expect(api.posts).toHaveLength(1);
  await expect(page.getByRole('heading', { name: '本次抢购未成功' })).not.toBeVisible();
  scenario.failSubmit = false; scenario.failQuery = false;
  await page.getByRole('button', { name: '重试原请求', exact: true }).click();
  await expect(page.getByRole('heading', { name: '订单创建成功', exact: true })).toBeVisible();
  expect(api.posts).toHaveLength(2);
  expect(api.posts[1]).toBe(api.posts[0]);
});

test('刷新后库存为零仍能找回进度，不重新提交', async ({ page }) => {
  const scenario: Scenario = { failQuery: true };
  const api = await setup(page, scenario);
  await openPurchase(page);
  await expect(page.getByText('暂时无法确认结果，请稍后继续查询；原请求编号已保留。')).toBeVisible();
  const original = api.posts[0];
  await page.keyboard.press('Escape');
  scenario.stock = 0; scenario.failQuery = false;
  await page.reload();
  await page.getByRole('button', { name: '查看抢购进度', exact: true }).click();
  await expect(page.getByRole('heading', { name: '订单创建成功', exact: true })).toBeVisible();
  expect(api.posts).toEqual([original]);
  await expect(page.locator('.request-id')).toHaveText(original);
});

test('业务拒绝展示中文原因，只有明确重新发起才生成新编号', async ({ page }) => {
  const scenario: Scenario = { rejected: true };
  const api = await setup(page, scenario);
  await openPurchase(page);
  await expect(page.getByText('库存不足', { exact: true })).toBeVisible();
  expect(api.posts).toHaveLength(1);
  expect(api.queries()).toBe(0);
  await page.getByRole('button', { name: '重新发起抢购', exact: true }).click();
  scenario.rejected = false;
  await page.getByRole('button', { name: '确认抢购', exact: true }).click();
  await expect(page.getByRole('heading', { name: '订单创建成功', exact: true })).toBeVisible();
  expect(api.posts[1]).not.toBe(api.posts[0]);
});

test('暂时查不到请求继续确认，不显示业务失败或自动新增提交', async ({ page }) => {
  const api = await setup(page, { notFoundOnce: true });
  await openPurchase(page);
  await expect(page.getByRole('heading', { name: '订单创建成功', exact: true })).toBeVisible();
  expect(api.posts).toHaveLength(1);
  expect(api.queries()).toBeGreaterThan(1);
});

test('同一券不会恢复另一个用户保留的请求', async ({ page }) => {
  await page.addInitScript(() => sessionStorage.setItem('hmdp-seckill-request:1:99', JSON.stringify({ requestId: 'user-one-request' })));
  const api = await setup(page, { userId: 2 });
  await page.goto('/shops/1?preview=0');
  await page.getByRole('button', { name: '立即抢购', exact: true }).click();
  await expect(page.getByRole('heading', { name: '确认抢购', exact: true })).toBeVisible();
  expect(api.posts).toHaveLength(0);
  expect(api.queries()).toBe(0);
});

test('已有进度无法读取时禁止覆盖原记录并重新下单', async ({ page }) => {
  await page.addInitScript(() => sessionStorage.setItem('hmdp-seckill-request:1:99', 'invalid-json'));
  const api = await setup(page);
  await page.goto('/shops/1?preview=0');
  await page.getByRole('button', { name: '查看抢购进度', exact: true }).click();
  await expect(page.getByText('暂时无法读取已有抢购进度，请检查浏览器设置后重试。')).toBeVisible();
  await expect(page.getByRole('button', { name: '确认抢购', exact: true })).toBeDisabled();
  expect(api.posts).toHaveLength(0);
  expect(await page.evaluate(() => sessionStorage.getItem('hmdp-seckill-request:1:99'))).toBe('invalid-json');
});
