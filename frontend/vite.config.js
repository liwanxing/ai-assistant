import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// Vite 构建配置 + 开发服务器配置
//
// 跨域问题与解决方案（面试重点）：
//   什么是跨域：协议+域名+端口 有一个不同就算跨域
//   为什么会跨域：浏览器同源策略，JS 不能随便请求别的域的接口
//   为什么代理能解决：浏览器只检查请求是否同域，代理让请求看起来同域
//     浏览器 → localhost:5173/api/login （同域，放行）
//     Vite转发 → localhost:8080/login    （服务器到服务器，浏览器管不着）
//
// 开发用 Vite 代理，上线用 Nginx 代理，逻辑一样、工具不同：
//   npm run dev  → vite.config.js proxy 生效，开发服务器转发
//   npm run build → 只输出静态文件，代理配置不参与打包
//   上线后 → Nginx 读自己的 nginx.conf 做同样的转发
//   所以这个配置文件永远保留，上线后自然不生效（开发服务器没跑）
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    // 代理：前端请求 /api 开头 → 转发到后端 8080
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        // 去掉 /api 前缀：/api/users → 后端收到 /users
        rewrite: (path) => path.replace(/^\/api/, ''),
      },
    },
  },
})
