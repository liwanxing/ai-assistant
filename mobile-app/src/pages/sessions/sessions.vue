<template>
  <view class="sessions-page">
    <scroll-view class="session-list" scroll-y>
      <view v-if="sessions.length === 0" class="empty-state">
        <text class="empty-text">暂无历史会话</text>
      </view>

      <view
        v-for="session in sessions"
        :key="session.sessionId"
        class="session-item"
        @click="openSession(session)"
      >
        <view class="session-info">
          <text class="session-title">{{ session.title }}</text>
          <text class="session-time">{{ session.activeTime }}</text>
        </view>
        <text class="session-arrow">›</text>
      </view>
    </scroll-view>
  </view>
</template>

<script setup lang="ts">
import { ref } from 'vue'

// TODO: Step 6 接入 GET /rag/sessions API
const sessions = ref([
  { sessionId: 'mock-1', title: '示例会话：关于 Java 学习', activeTime: '2026-08-16 10:30' },
  { sessionId: 'mock-2', title: '示例会话：Vue 3 入门', activeTime: '2026-08-15 14:20' }
])

const openSession = (session: any) => {
  // TODO: Step 6 带 sessionId 返回对话页，加载历史消息
  uni.showToast({ title: `打开：${session.title}`, icon: 'none' })
  setTimeout(() => {
    uni.navigateBack()
  }, 1000)
}
</script>

<style>
.sessions-page {
  height: 100vh;
  background-color: #f5f5f5;
}
.session-list {
  height: 100%;
  padding: 12px;
}
.empty-state {
  display: flex;
  justify-content: center;
  margin-top: 120px;
}
.empty-text {
  font-size: 15px;
  color: #999;
}
.session-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background-color: #fff;
  border-radius: 10px;
  padding: 14px;
  margin-bottom: 10px;
}
.session-info {
  flex: 1;
  margin-right: 10px;
}
.session-title {
  font-size: 15px;
  color: #333;
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.session-time {
  font-size: 12px;
  color: #999;
  margin-top: 4px;
  display: block;
}
.session-arrow {
  font-size: 20px;
  color: #ccc;
}
</style>
