<script setup>
import { ref, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { Fold, Expand, SwitchButton } from '@element-plus/icons-vue'
import request from '../utils/request'

// 布局组件 = 后台管理系统的"外壳"，所有业务页面都套在这个里面
// 类似后端的统一拦截器/过滤器，每个页面都经过这里

const route = useRoute()
const router = useRouter()

// 侧边栏折叠状态：点折叠按钮切换，收起后只显示图标
const isCollapse = ref(false)

// 面包屑数据：从路由 matched 记录中提取
// route.matched = 当前 URL 匹配到的所有路由记录（父+子，类似后端的拦截器链）
// 过滤出有 meta.title 的记录，用于显示面包屑
const breadcrumbs = computed(() => {
  return route.matched.filter(item => item.meta && item.meta.title)
})

// 当前激活的菜单项：用完整路径高亮对应菜单
const activeMenu = computed(() => route.path)

// 侧边栏菜单：从路由配置自动生成，不写死
// router.options.routes = 所有路由定义
// 找到 path: '/' 的那个（MainLayout 父路由），取它的 children
// 过滤出有 meta.title 的子路由 = 有标题的才显示在菜单里
const menuRoutes = computed(() => {
  const layoutRoute = router.options.routes.find(r => r.path === '/')
  if (layoutRoute && layoutRoute.children) {
    return layoutRoute.children.filter(r => r.meta && r.meta.title)
  }
  return []
})

// 退出登录：调后端 /logout 销毁 token，清前端 token，跳登录页
const handleLogout = () => {
  ElMessageBox.confirm('确定退出登录吗？', '提示', {
    type: 'warning',
    confirmButtonText: '确定',
    cancelButtonText: '取消',
  }).then(async () => {
    try {
      await request.get('/logout')
    } catch {
      // 后端报错也清前端 token（防止 token 残留）
    } finally {
      localStorage.removeItem('satoken')
      router.push('/login')
    }
  }).catch(() => {})
}
</script>

<template>
  <!-- el-container = Element Plus 的布局容器 -->
  <!-- 整体结构：顶部导航栏 + (左侧菜单 + 右侧主内容区) -->
  <el-container class="layout-container">
    <!-- ==================== 顶部导航栏 ==================== -->
    <el-header class="header">
      <div class="header-left">
        <!-- 折叠按钮：点击切换侧边栏宽度 -->
        <el-icon class="collapse-btn" @click="isCollapse = !isCollapse">
          <Fold v-if="!isCollapse" />
          <Expand v-else />
        </el-icon>
        <span class="app-title">学习项目管理系统</span>
      </div>
      <div class="header-right">
        <el-button
          type="danger"
          size="small"
          :icon="SwitchButton"
          @click="handleLogout"
        >
          退出登录
        </el-button>
      </div>
    </el-header>

    <el-container>
      <!-- ==================== 侧边栏菜单 ==================== -->
      <el-aside :width="isCollapse ? '64px' : '200px'" class="sidebar">
        <!-- el-menu router 模式：点击菜单项自动跳转路由 -->
        <!-- :default-active 高亮当前路由对应的菜单项 -->
        <el-menu
          :default-active="activeMenu"
          :collapse="isCollapse"
          router
          background-color="#304156"
          text-color="#bfcbd9"
          active-text-color="#409eff"
        >
          <!-- 从路由配置自动生成菜单项，不再写死 -->
          <!-- v-for 遍历 menuRoutes，每个路由生成一个菜单项 -->
          <el-menu-item
            v-for="item in menuRoutes"
            :key="item.path"
            :index="'/' + item.path"
          >
            <!-- component :is 动态渲染图标组件 -->
            <!-- item.meta.icon = 'User' → 渲染 User 图标组件 -->
            <el-icon><component :is="item.meta.icon" /></el-icon>
            <span>{{ item.meta.title }}</span>
          </el-menu-item>
        </el-menu>
      </el-aside>

      <!-- ==================== 右侧主内容区 ==================== -->
      <el-main class="main-content">
        <!-- 面包屑导航：显示当前页面位置，类似文件路径 -->
        <el-breadcrumb separator="/" class="breadcrumb">
          <el-breadcrumb-item :to="{ path: '/user' }">首页</el-breadcrumb-item>
          <el-breadcrumb-item
            v-for="item in breadcrumbs"
            :key="item.path"
          >
            {{ item.meta.title }}
          </el-breadcrumb-item>
        </el-breadcrumb>

        <!-- 子路由出口：渲染用户管理页等具体页面 -->
        <!-- 类似后端的 DispatcherServlet 转发到具体 Controller -->
        <div class="page-container">
          <router-view />
        </div>
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
/* 整体布局撑满屏幕高度 */
.layout-container {
  height: 100vh;
}

/* 顶部导航栏：深色背景，flex 两端对齐 */
.header {
  background-color: #242f42;
  color: #fff;
  display: flex;
  justify-content: space-between;
  align-items: center;
  height: 60px;
}
.header-left {
  display: flex;
  align-items: center;
  gap: 12px;
}
.collapse-btn {
  cursor: pointer;
  font-size: 20px;
}
.app-title {
  font-size: 18px;
  font-weight: bold;
}

/* 侧边栏：深色背景，折叠时宽度过渡动画 */
.sidebar {
  background-color: #304156;
  transition: width 0.3s;
  overflow-x: hidden;
}
.sidebar .el-menu {
  border-right: none;
}

/* 主内容区：浅灰背景 */
.main-content {
  background-color: #f0f2f5;
  padding: 20px;
}
.breadcrumb {
  margin-bottom: 20px;
}

/* 页面容器：白色卡片背景，撑满剩余高度 */
.page-container {
  background-color: #fff;
  border-radius: 4px;
  padding: 20px;
  min-height: calc(100vh - 140px);
}
</style>
