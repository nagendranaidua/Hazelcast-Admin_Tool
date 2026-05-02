import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Vite dev server proxies /api -> Spring Boot. In production, Apache HTTPD does the same.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api':      { target: 'http://localhost:8080', changeOrigin: true },
      '/sse':      { target: 'http://localhost:8080', changeOrigin: true },
      '/api-docs': { target: 'http://localhost:8080', changeOrigin: true },
      '/swagger-ui': { target: 'http://localhost:8080', changeOrigin: true },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: true,
  },
});
