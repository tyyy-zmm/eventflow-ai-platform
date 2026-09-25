import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  return {
    plugins: [react()],
    server: {
      port: Number(env.DEV_PORT || 4176),
      proxy: { '/v2': { target: env.API_TARGET || 'http://127.0.0.1:8093', changeOrigin: false } },
    },
  }
})
