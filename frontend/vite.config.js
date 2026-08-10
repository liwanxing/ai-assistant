import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    // 代理配置：前端请求 /api 开头的地址时，自动转发到后端 Spring Boot
    // 这样开发时前后端各跑各的，不会有跨域问题
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        // 重写路径：去掉 /api 前缀，后端接收到的就是原始路径
        // 例如前端请求 /api/user/list → 后端收到 /user/list
        rewrite: (path) => path.replace(/^\/api/, ''),
      },
    },
  },
})
