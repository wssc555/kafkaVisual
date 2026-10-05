import {defineConfig} from 'vite'
import vue from '@vitejs/plugin-vue'
import {fileURLToPath} from 'node:url'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    // tsconfig paths 声明了 @/* → ./src/*，但 vite/vitest 原本没有解析配置，
    // 一旦代码开始用 @/ 导入就会构建失败。这里与 tsconfig 保持同源。
    alias: {'@': fileURLToPath(new URL('./src', import.meta.url))},
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
  },
  server: {
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
})