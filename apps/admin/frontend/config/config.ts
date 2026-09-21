// @ts-expect-error Umi's generated runtime export is available during config loading.
import { defineConfig } from '@umijs/max';

import routes from './routes';

const apiTarget = process.env.ADMIN_API_TARGET || 'http://127.0.0.1:8080';

export default defineConfig({
  antd: {},
  access: {},
  esbuildMinifyIIFE: true,
  fastRefresh: true,
  hash: true,
  initialState: {},
  layout: {
    contentWidth: 'Fluid',
    fixSiderbar: true,
    fixedHeader: true,
    locale: false,
    navTheme: 'light',
    siderWidth: 236,
    title: 'Codex Quota Guard',
  },
  model: {},
  npmClient: 'pnpm',
  proxy: {
    '/api': {
      changeOrigin: true,
      target: apiTarget,
    },
  },
  request: {},
  routes,
  title: 'Codex Quota Guard 管理端',
});
