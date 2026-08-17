<template>
  <view class="chat-page">
    <view class="nav-bar">
      <text class="nav-title">✨ AI 助手</text>
      <view class="nav-right" @click="goSessions">
        <text class="nav-icon">📋</text>
      </view>
    </view>

    <scroll-view class="msg-list" scroll-y :scroll-into-view="scrollTarget" scroll-with-animation>
      <view class="empty-hint" v-if="messages.length === 0">
        <text class="empty-icon"><view class="mini-bubble"><view class="mb-body"></view><view class="mb-tail"></view></view></text>
        <text class="empty-text">你好，有什么可以帮你的？</text>
      </view>

      <view v-for="(msg, index) in messages" :key="index" :id="'msg-' + index"
        class="msg-row" :class="msg.role === 'user' ? 'msg-user' : 'msg-ai'">
        <view class="msg-bubble" :class="msg.role === 'user' ? 'bubble-user' : 'bubble-ai'">
          <image v-if="msg.imageUrl" class="msg-image" :src="BASE_URL + msg.imageUrl" mode="widthFix"
            @click="previewImage(msg.imageUrl)" />
          <text v-if="msg.role === 'user' && msg.content" class="msg-text">{{ msg.content }}</text>
          <rich-text v-else-if="msg.role !== 'user'" class="msg-text markdown-body" :nodes="renderMarkdown(msg.content)" />
        </view>
      </view>

      <view v-if="streaming" class="msg-row msg-ai">
        <view class="msg-bubble bubble-ai">
          <text v-if="streamText === ''" class="typing-dots">思考中...</text>
          <rich-text v-else class="msg-text markdown-body" :nodes="renderMarkdown(streamText)" />
        </view>
      </view>

      <view id="msg-bottom" style="height: 10px;"></view>
    </scroll-view>

    <view class="input-bar">
      <input class="msg-input" v-model="inputText" placeholder="输入消息..."
        confirm-type="send" :disabled="streaming" @confirm="sendMessage" />
      <view class="send-btn" :class="{ active: inputText.trim() && !streaming }" @click="sendMessage">
        <text class="send-icon">↑</text>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref, nextTick } from 'vue'
import { onLoad, onUnload } from '@dcloudio/uni-app'
import { chatStream, getSessionMessages } from '../../api/chat'
import { renderMarkdown } from '../../utils/markdown'
import { BASE_URL } from '../../api/request'

interface ChatMessage { role: string; content: string; imageUrl?: string }

const messages = ref<ChatMessage[]>([])
const inputText = ref('')
const streaming = ref(false)
const streamText = ref('')
let requestTask: any = null
const sessionId = ref('s-' + Date.now() + '-' + Math.random().toString(36).slice(2, 8))
const scrollTarget = ref('msg-bottom')

// ---- 历史会话接入：sessions 页通过事件总线通知（navigateBack 方向传值）----
// 打开历史会话：换 sessionId + 拉取历史消息；续聊时后端 ChatMemory 自动带上上下文
const switchSessionHandler = async (payload: { sessionId: string; title: string }) => {
  sessionId.value = payload.sessionId
  messages.value = []
  try {
    const list = await getSessionMessages(payload.sessionId)
    messages.value = list.map((m: any) => ({ role: m.role, content: m.content, imageUrl: m.imageUrl }))
    scrollToBottom()
  } catch (e: any) {
    uni.showToast({ title: e.message || '历史消息加载失败', icon: 'none' })
  }
}

// 新会话：重置 sessionId，清空消息
const newChatHandler = () => {
  sessionId.value = 's-' + Date.now() + '-' + Math.random().toString(36).slice(2, 8)
  messages.value = []
}

onLoad(() => {
  uni.$on('switchSession', switchSessionHandler)
  uni.$on('newChat', newChatHandler)
})

// 页面卸载时解绑，避免重复注册（tab 页常驻，主要是保险）
onUnload(() => {
  uni.$off('switchSession', switchSessionHandler)
  uni.$off('newChat', newChatHandler)
})

// 点击图片消息全屏预览
const previewImage = (url: string) => {
  uni.previewImage({ urls: [BASE_URL + url] })
}

const sendMessage = () => {
  const text = inputText.value.trim()
  if (!text || streaming.value) return
  messages.value.push({ role: 'user', content: text })
  inputText.value = ''
  scrollToBottom()
  streaming.value = true
  streamText.value = ''
  requestTask = chatStream({
    question: text,
    sessionId: sessionId.value,
    onChunk: (chunk: string) => {
      streamText.value += chunk
      scrollToBottom()
    },
    onDone: () => {
      if (streamText.value) {
        messages.value.push({ role: 'ai', content: streamText.value })
      }
      streamText.value = ''
      streaming.value = false
      scrollToBottom()
    },
    onError: (err: any) => {
      streaming.value = false
      streamText.value = ''
      // 不能静默失败：网络断了/token 过期时用户需要知道“为什么没回复”
      const msg = (err && err.message) || '网络异常，请稍后重试'
      uni.showToast({ title: msg, icon: 'none' })
    }
  })
}

const scrollToBottom = () => {
  nextTick(() => {
    scrollTarget.value = ''
    nextTick(() => { scrollTarget.value = 'msg-bottom' })
  })
}

const goSessions = () => {
  uni.navigateTo({ url: '/pages/sessions/sessions' })
}
</script>

<style>
.chat-page { display: flex; flex-direction: column; height: 100vh; background-color: #f5f5f5; }
.nav-bar { display: flex; align-items: center; justify-content: space-between; padding: 0 16px; height: 44px; background-color: #fff; border-bottom: 1px solid #eee; }
.nav-title { font-size: 17px; font-weight: 600; }
.nav-right { padding: 4px; }
.nav-icon { font-size: 20px; }
.msg-list { flex: 1; padding: 16px; }
.empty-hint { display: flex; flex-direction: column; align-items: center; margin-top: 120px; }
.empty-icon { margin-bottom: 16px; }
.mini-bubble { width: 48px; height: 36px; position: relative; }
.mb-body { width: 48px; height: 30px; border: 2.5px solid #c0c4cc; border-radius: 14px 14px 14px 4px; }
.mb-tail { position: absolute; bottom: 0; left: 0; width: 0; height: 0; border-top: 8px solid #c0c4cc; border-left: 6px solid transparent; margin-left: 2px; }
.empty-text { font-size: 15px; color: #999; }
.msg-row { display: flex; margin-bottom: 12px; }
.msg-user { justify-content: flex-end; }
.msg-ai { justify-content: flex-start; }
.msg-bubble { max-width: 65%; padding: 10px 14px; border-radius: 12px; font-size: 15px; line-height: 1.6; }
.bubble-user { background-color: #409EFF; color: #fff; margin-right: 36px; }
.bubble-ai { background-color: #fff; color: #333; margin-left: 36px; }
.msg-text { font-size: 15px; line-height: 1.6; word-break: break-all; }
.msg-image { width: 160px; border-radius: 8px; margin-bottom: 6px; display: block; }
.typing-dots { color: #999; font-size: 14px; }
.markdown-body { font-size: 15px; line-height: 1.7; }
.markdown-body pre { background-color: #f6f8fa; border-radius: 6px; padding: 10px 12px; margin: 8px 0; overflow-x: auto; }
.markdown-body code { background-color: #f0f0f0; padding: 2px 5px; border-radius: 3px; font-size: 0.9em; }
.markdown-body pre code { background: none; padding: 0; }
.input-bar { display: flex; align-items: center; padding: 8px 12px; padding-bottom: calc(8px + env(safe-area-inset-bottom)); background-color: #fff; border-top: 1px solid #eee; gap: 10px; }
.msg-input { flex: 1; height: 36px; padding: 0 12px; background-color: #f5f5f5; border-radius: 18px; font-size: 15px; }
.send-btn { width: 36px; height: 36px; display: flex; align-items: center; justify-content: center; background-color: #ccc; border-radius: 50%; }
.send-btn.active { background-color: #409EFF; }
.send-icon { color: #fff; font-size: 18px; font-weight: bold; }
</style>
