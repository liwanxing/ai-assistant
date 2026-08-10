import { createRouter, createWebHistory } from 'vue-router'

// 路由 = 前端的 URL 分发器（类似后端的 DispatcherServlet）
// 职责：URL 路径 → 对应的页面组件，加路由守卫检查登录状态
//
// 前端访问流程：
//   浏览器输入 URL → 路由匹配页面 → 路由守卫检查 token
//   → 有 token：放行，渲染页面
//   → 没 token：跳转 /login
//
// 路由嵌套结构（类似后端的统一拦截）：
//   /login → LoginView（独立页面，不套布局）
//   / → MainLayout（布局外壳）
//     └─ /user → UserView（子路由，渲染在布局的 router-view 里）
const routes = [
  // 登录页：独立页面，不需要导航栏和侧边栏
  {
    path: '/login',
    name: 'Login',
    // 懒加载：访问这个路径时才加载对应组件，不访问不加载
    component: () => import('../views/LoginView.vue'),
  },
  // 后台页面：套在 MainLayout 布局里
  {
    path: '/',
    // 布局组件作为父路由，子路由会渲染在布局的 <router-view /> 里
    component: () => import('../layout/MainLayout.vue'),
    // 登录后默认跳到用户管理页
    redirect: '/user',
    children: [
      {
        // 完整路径是 /user（父 path '/' + 子 path 'user'）
        path: 'user',
        name: 'User',
        // meta.title 用于面包屑和菜单显示
        // meta.icon 用于菜单图标（图标名对应 @element-plus/icons-vue 的组件名）
        meta: { title: '用户管理', icon: 'User' },
        component: () => import('../views/UserView.vue'),
      },
      // 以后加新页面，在这里加子路由
    ],
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
    // 已登录还去登录页？直接跳首页（避免重复登录）
    if (token) {
      next('/user')
    } else {
      next()
    }
  } else if (!token) {
    // 没有 token，跳转登录页
    next('/login')
  } else {
    // 有 token，正常访问
    next()
  }
})

export default router
