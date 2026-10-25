import { defineConfig } from '@playwright/test';
import base from './playwright.config';

process.env.HMDP_SECKILL_E2E = 'true';

// 独立端口和显式开关，保留默认旧入口的回归测试。
export default defineConfig({
  ...base,
  testMatch: '**/seckill-order.spec.ts',
  use: { ...base.use, baseURL: 'http://127.0.0.1:5174' },
  webServer: {
    command: 'npm run dev -- --port 5174 --strictPort',
    url: 'http://127.0.0.1:5174',
    reuseExistingServer: false,
    env: { VITE_RELIABLE_SECKILL_ENABLED: 'true' },
  },
});
