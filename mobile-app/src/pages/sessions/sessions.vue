<template>
  <view class="sessions-page">
    <view class="nav-bar">
      <text class="nav-title">历史会话</text>
      <view class="nav-right" @click="newChat">
        <text class="nav-icon">✚</text>
      </view>
    </view>

    <scroll-view class="session-list" scroll-y>
      <view v-if="loading" class="empty-state">
        <text class="empty-text">加载中...</text>
      </view>

      <view v-else-if="sessions.length === 0" class="empty-state">
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
          <text class="session-time">{{ formatTime(session.updateTime) }}</text>
        </view>
        <view class="session-actions">
          <text class="session-delete" @click.stop="confirmDelete(session)">🗑</text>
          <text class="session-arrow">›</text>
        </view>
      </view>
    </scroll-view>
  </view>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { getSessions, deleteSession } from '../../api/chat'

const sessions = ref<any[]>([])
const loading = ref(false)

// 每次进入页面都刷新：从对话页回来时可能有新产生的会话
onShow(() => {
  loadSessions()
})

const loadSessions = async () => {
  loading.value = true
  try {
    sessions.value = await getSessions()
  } catch (e: any) {
    uni.showToast({ title: e.message || '加载失败', icon: 'none' })
  } finally {
    loading.value = false
  }
}

/**
 * 打开历史会话：通过事件总线通知对话页切换 sessionId 并加载历史消息
 * （navigateBack 是"回去"方向，没法用 navigateTo 的 EventChannel，uni.$emit 最简单）
 * 续聊原理：对话页拿同一个 sessionId 发消息，后端 ChatMemory 自动带上历史上下文
 */
const openSession = (session: any) => {
  uni.$emit('switchSession', { sessionId: session.sessionId, title: session.title })
  uni.navigateBack()
}

/** 新会话：通知对话页重置 sessionId 和消息列表 */
const newChat = () => {
  uni.$emit('newChat')
  uni.navigateBack()
}

/** 删除会话（带二次确认）：后端四件套彻底删（消息+摘要+图片文件+会话记录） */
const confirmDelete = (session: any) => {
  uni.showModal({
    title: '删除会话',
    content: `确定删除「${session.title}」吗？聊天记录将一并删除`,
    confirmColor: '#f56c6c',
    success: async (res) => {
      if (!res.confirm) return
      try {
        await deleteSession(session.sessionId)
        uni.showToast({ title: '已删除', icon: 'success' })
        loadSessions()
      } catch (e: any) {
        uni.showToast({ title: e.message || '删除失败', icon: 'none' })
      }
    }
  })
}

/** 后端 LocalDateTime 序列化为 ISO 格式（2026-08-17T14:27:48.755），截取到分钟展示 */
const formatTime = (time: string) => {
  if (!time) return ''
  return time.replace('T', ' ').substring(0, 16)
}
</script>

<style>
.sessions-page {
  display: flex;
  flex-direction: column;
  height: 100vh;
  background-color: #f5f5f5;
}
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
  padding: 4px 8px;
}
.nav-icon {
  font-size: 18px;
}
.session-list {
  flex: 1;
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
.session-actions {
  display: flex;
  align-items: center;
}
.session-delete {
  font-size: 14px;
  padding: 4px 6px;
  opacity: 0.6;
}
.session-arrow {
  font-size: 20px;
  color: #ccc;
}
</style>
