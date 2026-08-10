import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import './style.css'
import App from './App.vue'
import router from './router/index.js'

// 创建 Vue 应用，类似后端的 SpringApplication.run()
// .use(ElementPlus) → 注册 Element Plus，全局可用 el-button、el-table 等组件
// .use(router)      → 注册路由，URL 路径对应页面组件
// .mount('#app')    → 挂载到 index.html 里的 <div id="app">
const app = createApp(App)
// 全局注册所有 Element Plus 图标组件（el-icon 里可以直接用）
for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}
app.use(ElementPlus).use(router).mount('#app')
