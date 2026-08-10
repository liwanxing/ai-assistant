<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import request from '../utils/request'

// 路由实例：用于登录成功后跳转页面
const router = useRouter()

// 表单数据（类似后端的 DTO）
const loginForm = ref({
  username: '',
  password: '',
})

// 按钮加载状态：点击登录后变 true，请求结束变 false
const loading = ref(false)

// 登录方法：调后端 GET /login?username=xxx&password=xxx
// 后端返回 { code: 200, data: { tokenName, tokenValue } }
const handleLogin = async () => {
  // 基本校验：空值不放过
  if (!loginForm.value.username || !loginForm.value.password) {
    return
  }
  loading.value = true
  try {
    // request.get 会经过拦截器：自动加 token、自动处理错误
    // 响应拦截器返回的是 res（整个 { code, message, data }），所以拿 res.data
    const res = await request.get('/login', {
      params: {
        username: loginForm.value.username,
        password: loginForm.value.password,
      },
    })
    // 登录成功：把 token 存到 localStorage（路由守卫和请求拦截器都读这个）
    localStorage.setItem('satoken', res.data.tokenValue)
    // 跳转到用户管理页
    router.push('/user')
  } catch {
    // 错误已在响应拦截器里弹了 ElMessage，这里不用再处理
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <!-- 居中布局：用 flex 把卡片放到页面正中间 -->
  <div class="login-container">
    <!-- el-card：Element Plus 的卡片组件，自带圆角和阴影 -->
    <el-card class="login-card">
      <h2 style="text-align: center; margin-bottom: 24px;">登录</h2>
      <!-- el-form：表单组件，label-position="top" 让标签在输入框上方 -->
      <el-form label-position="top" :model="loginForm">
        <el-form-item label="用户名">
          <!-- el-input：v-model 双向绑定，输入的值实时同步到 loginForm.username -->
          <el-input
            v-model="loginForm.username"
            placeholder="请输入用户名"
            @keyup.enter="handleLogin"
          />
        </el-form-item>
        <el-form-item label="密码">
          <el-input
            v-model="loginForm.password"
            type="password"
            placeholder="请输入密码"
            show-password
            @keyup.enter="handleLogin"
          />
        </el-form-item>
        <el-form-item>
          <!-- :loading 绑定按钮加载状态，点击后转圈，防止重复提交 -->
          <el-button
            type="primary"
            :loading="loading"
            style="width: 100%;"
            @click="handleLogin"
          >
            登录
          </el-button>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<style scoped>
/* scoped：样式只作用于当前组件，不影响其他页面 */
.login-container {
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: 100vh;
  background-color: #f0f2f5;
}
.login-card {
  width: 400px;
}
</style>
