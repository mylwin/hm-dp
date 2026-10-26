import { defineConfig } from '@playwright/test';
import base from './playwright.config';

// 独立开发服务验证开关，生产预览验证构建隔离；不调用真实运维接口。
export default defineConfig({
  ...base,
  testMatch: '**/seckill-maintenance.spec.ts',
  testIgnore: [],
  outputDir: 'test-results/maintenance',
  use: { ...base.use, baseURL: 'http://127.0.0.1:5175' },
  webServer: [
    {
      command: 'npm run dev -- --port 5175 --strictPort',
      env: { VITE_SECKILL_OPS_ENABLED: 'true' },
      url: 'http://127.0.0.1:5175', reuseExistingServer: false,
    },
    {
      command: 'npm run dev -- --port 5176 --strictPort',
      env: { VITE_SECKILL_OPS_ENABLED: 'false' },
      url: 'http://127.0.0.1:5176', reuseExistingServer: false,
    },
    {
      command: 'npm run preview -- --port 5177 --strictPort',
      url: 'http://127.0.0.1:5177', reuseExistingServer: false,
    },
  ],
});
