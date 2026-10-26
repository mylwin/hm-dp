import { test, expect, type Page } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { mkdir } from 'node:fs/promises';
import { isBackendApi } from './helpers/api-route';

const opsToken = 'local-maintenance-contract-token-2026';
const orderId = '856725678012345678';
const quarantineId = 'a'.repeat(64);
const message = {
  quarantine_id: quarantineId, source: 'DLQ', message_id: 'physical-message-001', topic: '%DLQ%hmdp-orders',
  body_hash: 'b'.repeat(64), error_type: 'DEAD_LETTER', reconsume_times: 16, created_at: '2026-10-25T09:10:00',
};
type Scenario = { loggedOut?: boolean; healthStatus?: number; quarantineStatus?: number; empty?: boolean; businessFailure?: boolean; networkFailure?: boolean; delayWrite?: boolean };
type Write = { path: string; body: Record<string, unknown>; authorization?: string; opsToken?: string };
async function setup(page: Page, scenario: Scenario = {}) {
  const reads: string[] = [];
  const writes: Write[] = [];
  await page.addInitScript(({ loggedOut }) => { if (!loggedOut) sessionStorage.setItem('token', 'maintenance-login-token'); }, { loggedOut: !!scenario.loggedOut });
  await page.route(isBackendApi, async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname.replace(/^\/api/, '');
    if (path === '/user/me') return route.fulfill({ json: { success: true, data: { id: 1, nickName: '本地测试用户' } } });
    // 离开工具页进入个人中心时，提供该页的只读数据。
    if (path.startsWith('/user/info/')) return route.fulfill({ json: { success: true, data: null } });
    if (path === '/blog/of/me') return route.fulfill({ json: { success: true, data: [] } });
    if (path === '/user/sign/count') return route.fulfill({ json: { success: true, data: 0 } });
    if (!path.startsWith('/ops/seckill/')) throw new Error(`维护页面不应请求其他业务接口：${path}`);
    expect(request.headers()['authorization']).toBe('maintenance-login-token');
    expect(request.headers()['x-seckill-ops-token']).toBe(opsToken);
    if (request.method() === 'POST') {
      writes.push({ path, body: request.postDataJSON(), authorization: request.headers()['authorization'], opsToken: request.headers()['x-seckill-ops-token'] });
      if (scenario.delayWrite) await new Promise((resolve) => setTimeout(resolve, 250));
      if (scenario.networkFailure) return route.abort('timedout');
      return route.fulfill({ json: scenario.businessFailure ? { success: false, errorMsg: '发送租约仍有效，不能重投' } : { success: true } });
    }
    reads.push(path);
    if (path.endsWith('/health')) {
      if (scenario.healthStatus) return route.fulfill({ status: scenario.healthStatus, json: { success: false } });
      return route.fulfill({ json: { success: true, data: { pending_tasks: 7, pending_create_events: 2, pending_confirmations: 3, pending_releases: 2,
        expired_leases: 0, max_attempts: 4, oldest_task_seconds: 90, unfinished_requests: 5, preparing_requests: 1, accepted_requests: 4,
        oldest_request_seconds: 120, quarantine_pending: scenario.empty ? 0 : 1, pending_campaigns: 0, oldest_campaign_seconds: 0, blocked_campaigns: 0, recovery_errors: 1 } } });
    }
    if (path.endsWith('/quarantine')) {
      if (scenario.quarantineStatus) return route.fulfill({ status: scenario.quarantineStatus, json: { success: false } });
      return route.fulfill({ json: { success: true, data: scenario.empty ? [] : [message] } });
    }
    throw new Error(`未定义的维护接口：${path}`);
  });
  return { reads, writes };
}
async function openWorkspace(page: Page) {
  await page.goto('/local/seckill?preview=0');
  await expect(page.getByLabel('独立运维令牌')).toBeVisible();
}
async function connect(page: Page) {
  await openWorkspace(page);
  await page.getByLabel('独立运维令牌').fill(opsToken);
  await page.getByRole('button', { name: '连接 / 刷新' }).click();
  await expect(page.getByRole('button', { name: '连接 / 刷新' })).toBeEnabled();
}

test('默认开关关闭时隐藏入口并拒绝直接路由', async ({ page }) => {
  const api = await setup(page);
  await page.goto('http://127.0.0.1:5176/local/seckill?preview=0');
  await expect(page.getByRole('heading', { name: '这个页面暂时找不到了' })).toBeVisible();
  await expect(page.getByRole('link', { name: '秒杀维护', exact: true })).toHaveCount(0);
  expect(api.reads).toHaveLength(0); expect(api.writes).toHaveLength(0);
});

test('生产构建隐藏维护入口并拒绝直接路由', async ({ page }) => {
  const api = await setup(page);
  await page.goto('http://127.0.0.1:5177/local/seckill?preview=0');
  await expect(page.getByRole('heading', { name: '这个页面暂时找不到了' })).toBeVisible();
  await expect(page.getByRole('link', { name: '秒杀维护', exact: true })).toHaveCount(0);
  expect(api.reads).toHaveLength(0); expect(api.writes).toHaveLength(0);
});

test('预览和未登录均不请求运维接口', async ({ page }) => {
  const api = await setup(page, { loggedOut: true });
  await page.goto('/local/seckill?preview=1');
  await expect(page.getByRole('heading', { name: '请切换到实时数据' })).toBeVisible();
  await page.goto('/local/seckill?preview=0');
  await expect(page.getByRole('heading', { name: '登录后使用维护工具' })).toBeVisible();
  expect(api.reads).toHaveLength(0); expect(api.writes).toHaveLength(0);
});

test('运维查询遇到登录失效时清除工作区和登录凭据', async ({ page }) => {
  const api = await setup(page, { healthStatus: 401 });
  await openWorkspace(page);
  await page.getByLabel('独立运维令牌').fill(opsToken);
  await page.getByRole('button', { name: '连接 / 刷新' }).click();
  await expect(page.getByRole('heading', { name: '登录，发现更多好生活' })).toBeVisible();
  await expect(page.getByLabel('独立运维令牌')).toHaveCount(0);
  expect(await page.evaluate(() => sessionStorage.getItem('token'))).toBeNull();
  expect(api.writes).toHaveLength(0);
});

test('显式连接查询指标与隔离摘要，令牌不落盘且离开页面清除', async ({ page }) => {
  const api = await setup(page);
  await openWorkspace(page);
  await expect(page.getByRole('button', { name: '连接 / 刷新' })).toBeDisabled();
  expect(api.reads).toHaveLength(0);
  await page.getByLabel('独立运维令牌').fill('too-short');
  await expect(page.getByRole('button', { name: '连接 / 刷新' })).toBeDisabled();
  await page.getByLabel('独立运维令牌').fill(opsToken);
  await page.getByRole('button', { name: '连接 / 刷新' }).click();
  await expect(page.locator('[data-metric="pending_tasks"]')).toHaveText('7');
  await expect(page.locator('[data-metric="oldest_task_seconds"]')).toHaveText('90秒');
  await expect(page.getByText('physical-message-001')).toBeVisible();
  await page.getByText('编号与摘要', { exact: true }).click();
  await expect(page.getByText(quarantineId, { exact: true })).toBeVisible();
  const stored = await page.evaluate(() => ({ local: { ...localStorage }, session: { ...sessionStorage }, url: location.href }));
  expect(JSON.stringify(stored)).not.toContain(opsToken);
  expect(api.writes).toHaveLength(0);
  await page.getByRole('link', { name: '个人中心', exact: true }).first().click();
  await expect(page.getByRole('heading', { name: '本地测试用户', exact: true })).toBeVisible();
  await page.getByRole('link', { name: '秒杀维护', exact: true }).click();
  await expect(page.getByLabel('独立运维令牌')).toHaveValue('');
});

test('重投保留完整长订单号、隔离关联与原因，确认前不写入，提交时防重复', async ({ page }) => {
  const api = await setup(page, { delayWrite: true });
  await connect(page);
  await page.getByRole('button', { name: '关联重投', exact: true }).click();
  await expect(page.getByLabel('关联隔离编号')).toHaveValue(quarantineId);
  await page.getByLabel('原订单号', { exact: true }).fill(orderId);
  await page.getByRole('button', { name: '重投原消息', exact: true }).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog.getByText(orderId, { exact: true })).toBeVisible();
  await expect(dialog.getByRole('button', { name: '确认重投原消息' })).toBeDisabled();
  expect(api.writes).toHaveLength(0);
  await dialog.getByLabel('操作原因').fill('核对原订单预占和死信后重投');
  await dialog.getByRole('button', { name: '确认重投原消息' }).click();
  await expect(dialog.getByRole('button', { name: '提交中…' })).toBeDisabled();
  await page.keyboard.press('Escape');
  await expect(dialog).toBeVisible();
  await expect(page.getByText('重投已提交，仍需等待消息投递及消费者建单。')).toBeVisible();
  expect(api.writes).toEqual([{ path: `/ops/seckill/orders/${orderId}/replay`, body: { reason: '核对原订单预占和死信后重投', quarantineId }, authorization: 'maintenance-login-token', opsToken }]);
  expect(api.reads).toHaveLength(4);
});

test('关闭未建单请求仅发送原因，提示库存异步回补', async ({ page }) => {
  const api = await setup(page);
  await connect(page);
  await page.getByLabel('原订单号', { exact: true }).fill(orderId);
  await page.getByLabel('关联隔离编号').fill(quarantineId);
  await page.getByRole('button', { name: '关闭抢购请求', exact: true }).click();
  await page.getByLabel('操作原因').fill('本地测试核对无订单，明确关闭');
  await page.getByRole('button', { name: '确认关闭抢购请求' }).click();
  await expect(page.getByText('请求已关闭，释放任务已提交；Redis 库存由后台任务随后回补。')).toBeVisible();
  expect(api.writes[0].path).toBe(`/ops/seckill/orders/${orderId}/close`);
  expect(api.writes[0].body).toEqual({ reason: '本地测试核对无订单，明确关闭' });
});

test('标记隔离消息已处置后刷新列表，取消确认不提交', async ({ page }) => {
  const scenario: Scenario = {};
  const api = await setup(page, scenario);
  await connect(page);
  await page.getByRole('button', { name: '标记已处置', exact: true }).click();
  await page.getByRole('button', { name: '取消', exact: true }).click();
  expect(api.writes).toHaveLength(0);
  await page.getByRole('button', { name: '标记已处置', exact: true }).click();
  await page.getByLabel('操作原因').fill('已核对，无需重投');
  scenario.empty = true;
  await page.getByRole('button', { name: '确认标记隔离消息已处置' }).click();
  await expect(page.getByText('当前没有未处置的隔离消息。')).toBeVisible();
  expect(api.writes[0].path).toBe(`/ops/seckill/quarantine/${quarantineId}/dismiss`);
  expect(api.writes[0].body).toEqual({ reason: '已核对，无需重投' });
});

test('无效订单和隔离编号在提交前拦截', async ({ page }) => {
  const api = await setup(page);
  await connect(page);
  for (const invalidOrderId of ['0', '-1', '1e10', '9223372036854775808']) {
    await page.getByLabel('原订单号', { exact: true }).fill(invalidOrderId);
    await page.getByRole('button', { name: '重投原消息', exact: true }).click();
    await expect(page.getByText('请输入有效的正数订单号，最大为 9223372036854775807。')).toBeVisible();
  }
  await page.getByLabel('原订单号', { exact: true }).fill(orderId);
  await page.getByLabel('关联隔离编号').fill('not-a-hash');
  await page.getByRole('button', { name: '重投原消息', exact: true }).click();
  await expect(page.getByText('关联隔离编号必须为 64 位小写十六进制字符串。')).toBeVisible();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  expect(api.writes).toHaveLength(0);
});

for (const [status, hint] of [[403, '无秒杀运维权限'], [404, '未找到运维接口'], [503, '后端暂时不可用']] as const) {
  test(`健康查询 ${status} 给出排查提示，保留独立成功的隔离列表`, async ({ page }) => {
    await setup(page, { healthStatus: status });
    await connect(page);
    await expect(page.getByRole('alert')).toContainText(hint);
    await expect(page.locator('[data-metric="pending_tasks"]')).toHaveText('—');
    await expect(page.getByText('physical-message-001')).toBeVisible();
  });
}

test('隔离查询失败不遮住健康结果，清除令牌后清空快照', async ({ page }) => {
  await setup(page, { quarantineStatus: 503 });
  await connect(page);
  await expect(page.locator('[data-metric="pending_tasks"]')).toHaveText('7');
  await expect(page.getByRole('alert')).toContainText('后端暂时不可用');
  await page.getByRole('button', { name: '清除令牌' }).click();
  await expect(page.getByLabel('独立运维令牌')).toHaveValue('');
  await expect(page.locator('[data-metric="pending_tasks"]')).toHaveText('—');
  await expect(page.getByRole('alert')).toHaveCount(0);
});

for (const networkFailure of [false, true]) {
  test(`${networkFailure ? '网络超时' : '业务拒绝'}保留处置原因，不自动重试`, async ({ page }) => {
    const api = await setup(page, { businessFailure: !networkFailure, networkFailure });
    await connect(page);
    await page.getByLabel('原订单号', { exact: true }).fill(orderId);
    await page.getByRole('button', { name: '重投原消息', exact: true }).click();
    await page.getByLabel('操作原因').fill('核对后尝试重投');
    await page.getByRole('button', { name: '确认重投原消息' }).click();
    await expect(page.getByRole('dialog').getByRole('alert')).toContainText(networkFailure ? '结果尚未确认' : '发送租约仍有效');
    await expect(page.getByLabel('操作原因')).toHaveValue('核对后尝试重投');
    expect(api.writes).toHaveLength(1);
  });
}

test('桌面与小屏深浅主题不溢出，通过可访问性检查', async ({ page }) => {
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await setup(page);
  await connect(page);
  await mkdir('test-results/maintenance/screenshots', { recursive: true });
  for (const theme of ['light', 'dark']) {
    await page.evaluate((value) => { document.documentElement.dataset.theme = value; }, theme);
    for (const width of [1440, 375]) {
      await page.setViewportSize({ width, height: 1000 });
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
      const audit = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze();
      expect(audit.violations).toEqual([]);
      await page.screenshot({ path: `test-results/maintenance/screenshots/seckill-maintenance-${theme}-${width}.png`, fullPage: true });
    }
  }
  await page.getByRole('button', { name: '标记已处置', exact: true }).click();
  const dialogAudit = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze();
  expect(dialogAudit.violations).toEqual([]);
});
