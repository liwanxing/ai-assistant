<template>
  <view class="chat-page">
    <!-- 顶部导航栏 -->
    <view class="nav-bar">
      <text class="nav-title">AI 助手</text>
      <view class="nav-right" @click="goSessions">
        <text class="nav-icon">📋</text>
      </view>
    </view>

    <!-- 消息列表区域 -->
    <scroll-view class="msg-list" scroll-y>
      <view class="empty-hint" v-if="messages.length === 0">
        <text class="empty-icon">✨</text>
        <text class="empty-text">你好，有什么可以帮你的？</text>
      </view>

      <view
        v-for="(msg, index) in messages"
        :key="index"
        class="msg-row"
        :class="msg.role === 'user' ? 'msg-user' : 'msg-ai'"
      >
        <view class="msg-bubble" :class="msg.role === 'user' ? 'bubble-user' : 'bubble-ai'">
          <text>{{ msg.content }}</text>
        </view>
      </view>
    </scroll-view>

    <!-- 输入区域 -->
    <view class="input-bar">
      <input
        class="msg-input"
        v-model="inputText"
        placeholder="输入消息..."
        confirm-type="send"
        @confirm="sendMessage"
      />
      <view class="send-btn" @click="sendMessage">
        <text class="send-icon">↑</text>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref } from 'vue'

const messages = ref([
  { role: 'ai', content: '你好！我是 AI 助手，有什么可以帮你的？' }
])
const inputText = ref('')

const sendMessage = () => {
  const text = inputText.value.trim()
  if (!text) return

  messages.value.push({ role: 'user', content: text })
  inputText.value = ''

  // TODO: Step 5 接入后端 API，替换为真实对话
  setTimeout(() => {
    messages.value.push({ role: 'ai', content: `收到：${text}` })
  }, 500)
}

const goSessions = () => {
  uni.navigateTo({ url: '/pages/sessions/sessions' })
}
</script>

<style>
.chat-page {
  display: flex;
  flex-direction: column;
  height: 100vh;
  background-color: #f5f5f5;
}

/* 顶部导航 */
.nav-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 16px;
  height: 44px;
  background-color: #fff;
  border-bottom: 1px solid #eee;
}
.nav-title {
  font-size: 17px;
  font-weight: 600;
}
.nav-right {
  padding: 4px;
}
.nav-icon {
  font-size: 20px;
}

/* 消息列表 */
.msg-list {
  flex: 1;
  padding: 16px;
}
.empty-hint {
  display: flex;
  flex-direction: column;
  align-items: center;
  margin-top: 120px;
}
.empty-icon {
  font-size: 60px;
  margin-bottom: 16px;
}
.empty-text {
  font-size: 15px;
  color: #999;
}

/* 消息气泡 */
.msg-row {
  display: flex;
  margin-bottom: 12px;
}
.msg-user {
  justify-content: flex-end;
}
.msg-ai {
  justify-content: flex-start;
}
.msg-bubble {
  max-width: 75%;
  padding: 10px 14px;
  border-radius: 12px;
  font-size: 15px;
  line-height: 1.5;
}
.bubble-user {
  background-color: #409EFF;
  color: #fff;
}
.bubble-ai {
  background-color: #fff;
  color: #333;
}

/* 输入栏 */
.input-bar {
  display: flex;
  align-items: center;
  padding: 8px 12px;
  padding-bottom: calc(8px + env(safe-area-inset-bottom));
  background-color: #fff;
  border-top: 1px solid #eee;
  gap: 10px;
}
.msg-input {
  flex: 1;
  height: 36px;
  padding: 0 12px;
  background-color: #f5f5f5;
  border-radius: 18px;
  font-size: 15px;
}
.send-btn {
  width: 36px;
  height: 36px;
  display: flex;
  align-items: center;
  justify-content: center;
  background-color: #409EFF;
  border-radius: 50%;
}
.send-icon {
  color: #fff;
  font-size: 18px;
  font-weight: bold;
}
</style>
