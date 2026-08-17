<template>
  <view class="login-page">
    <view class="login-card">
      <text class="login-title">✨ AI 助手</text>
      <text class="login-subtitle">登录后开始对话</text>

      <view class="form-group">
        <text class="form-label">用户名</text>
        <input
          class="form-input"
          v-model="username"
          placeholder="请输入用户名"
          @keyup.enter="handleLogin"
        />
      </view>

      <view class="form-group">
        <text class="form-label">密码</text>
        <input
          class="form-input"
          v-model="password"
          type="password"
          placeholder="请输入密码"
          @keyup.enter="handleLogin"
        />
      </view>

      <view class="login-btn" :class="{ disabled: loading }" @click="handleLogin">
        <text class="btn-text">{{ loading ? '登录中...' : '登录' }}</text>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { login } from '../../api/auth'

const username = ref('')
const password = ref('')
const loading = ref(false)

/**
 * 登录流程（对标 PC 端 LoginView.vue 的 handleLogin）：
 *
 * PC 端：
 *   const res = await request.post('/login', form)
 *   localStorage.setItem('satoken', res.data.tokenValue)
 *   router.push('/user')
 *
 * 小程序端：
 *   const data = await login(username, password)  ← 内部自动 setToken
 *   uni.switchTab('/pages/index/index')           ← switchTab 只能跳 TabBar 页面
 */
const handleLogin = async () => {
  if (!username.value || !password.value) {
    uni.showToast({ title: '请填写用户名和密码', icon: 'none' })
    return
  }
  loading.value = true
  try {
    await login(username.value, password.value)
    uni.showToast({ title: '登录成功', icon: 'success' })
    // 登录成功 → 跳转到对话页（TabBar 页面必须用 switchTab）
    setTimeout(() => {
      uni.switchTab({ url: '/pages/index/index' })
    }, 500)
  } catch (e) {
    // 错误已在 request.js 的拦截器里处理（弹 toast）
  } finally {
    loading.value = false
  }
}
</script>

<style>
.login-page {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100vh;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
}
.login-card {
  width: 85%;
  background-color: #fff;
  border-radius: 16px;
  padding: 36px 24px;
  box-shadow: 0 8px 32px rgba(0,0,0,0.15);
}
.login-title {
  display: block;
  text-align: center;
  font-size: 24px;
  font-weight: 700;
  margin-bottom: 8px;
}
.login-subtitle {
  display: block;
  text-align: center;
  font-size: 14px;
  color: #999;
  margin-bottom: 32px;
}
.form-group {
  margin-bottom: 18px;
}
.form-label {
  font-size: 14px;
  color: #333;
  margin-bottom: 6px;
  display: block;
}
.form-input {
  width: 100%;
  height: 44px;
  padding: 0 14px;
  border: 1px solid #ddd;
  border-radius: 8px;
  font-size: 15px;
  box-sizing: border-box;
}
.login-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 44px;
  background-color: #409EFF;
  border-radius: 8px;
  margin-top: 24px;
}
.login-btn.disabled {
  opacity: 0.6;
}
.btn-text {
  color: #fff;
  font-size: 16px;
  font-weight: 500;
}
</style>
