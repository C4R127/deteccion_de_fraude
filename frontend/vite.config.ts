/// <reference types="vitest/config" />
import path from 'node:path';
import tailwindcss from '@tailwindcss/vite';
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

// En desarrollo la API y el WebSocket pasan por el gateway (8080) a través del proxy de
// Vite: el navegador habla solo con el origen 5173 y no hace falta CORS en el gateway.
const GATEWAY = process.env.VITE_GATEWAY ?? 'http://localhost:8080';

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: { '@': path.resolve(__dirname, 'src') },
  },
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': { target: GATEWAY, changeOrigin: false },
      '/ws': { target: GATEWAY, ws: true, changeOrigin: false },
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/pruebas/setup.ts'],
    css: false,
  },
});
