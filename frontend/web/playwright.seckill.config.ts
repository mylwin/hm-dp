import { defineConfig } from '@playwright/test';
import base from './playwright.config';

// 独立端口运行默认新链路的抢购契约测试，不复用用户已有开发服务。
export default defineConfig({
  ...base,
  testMatch: '**/{seckill-order,storefront}.spec.ts',
  use: { ...base.use, baseURL: 'http://127.0.0.1:5174' },
  webServer: {
    command: 'npm run dev -- --port 5174 --strictPort',
    url: 'http://127.0.0.1:5174',
    reuseExistingServer: false,
  },
});
