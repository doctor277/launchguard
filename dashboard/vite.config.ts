import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

export default defineConfig({
  plugins: [react()],
  server: {
    port: Number(process.env.DASHBOARD_PORT ?? 3001),
    strictPort: true,
    proxy: { '/api': { target: process.env.DASHBOARD_BACKEND_URL ?? 'http://localhost:8080', changeOrigin: true } },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['src/test/setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
    restoreMocks: true,
    reporters: ['default', 'junit'],
    outputFile: { junit: 'test-results/unit/vitest.xml' },
  },
})
