import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    environment: 'jsdom',
    restoreMocks: true,
    setupFiles: ['./src/test/setup.ts'],
    testTimeout: 30_000,
  },
});
