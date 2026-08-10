import { createRouter, createWebHistory } from 'vue-router'

// 路由 = 前端的 URL 分发器（类似后端的 DispatcherServlet）
// 职责：URL 路径 → 对应的页面组件，加路由守卫检查登录状态
//
// 前端访问流程：
//   浏览器输入 URL → 路由匹配页面 → 路由守卫检查 token
//   → 有 token：放行，渲染页面
//   → 没 token：跳转 /login
const routes = [
  {
    path: '/login',
    name: 'Login',
    // 懒加载：访问这个路径时才加载对应组件，不访问不加载
    component: () => import('../views/LoginView.vue'),
  },
  {
    path: '/user',
    name: 'User',
    component: () => import('../views/UserView.vue'),
  },
  {
    // 根路径重定向到登录页
    path: '/',
    redirect: '/login',
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

// 路由守卫：每次页面跳转前检查是否已登录
// 类似后端的 SaInterceptor，拦截所有请求检查 token
router.beforeEach((to, from, next) => {
  const token = localStorage.getItem('satoken')
  if (to.path === '/login') {
    // 登录页不需要 token，直接放行
    next()
  } else if (!token) {
    // 没有 token，跳转登录页
    next('/login')
  } else {
    // 有 token，正常访问
    next()
  }
})

export default router
