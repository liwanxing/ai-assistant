<script setup>
import { ref, reactive, nextTick } from 'vue'
import { ElMessage } from 'element-plus'
import { ChatDotRound, Promotion, Plus } from '@element-plus/icons-vue'

// ──────────────────────────────────────
// 数据定义
// ──────────────────────────────────────

// 会话 ID：前端生成，同一个 sessionId 下的问题共享对话历史（后端 ChatMemory 按 sessionId 存取）
const sessionId = ref(crypto.randomUUID())
const messages = ref([])
const inputQuestion = ref('')
const asking = ref(false)
const chatBodyRef = ref(null)

// 新对话：换一个新的 sessionId，清空聊天记录
const newChat = () => {
  sessionId.value = crypto.randomUUID()
  messages.value = []
}

// ──────────────────────────────────────
// 发送问题：GET /rag/ask?question=xxx（SSE 流式输出）
// 不用 axios（axios 不支持流式读取），改用原生 fetch + ReadableStream
// 效果：AI 回答一个字就显示一个字，打字机效果，不用等全部生成完
// ──────────────────────────────────────

const handleAsk = async () => {
  const question = inputQuestion.value.trim()
  if (!question) return

  messages.value.push({ role: 'user', content: question })
  inputQuestion.value = ''
  asking.value = true

  const aiMessage = reactive({ role: 'ai', content: '' })
  messages.value.push(aiMessage)

  try {
    const token = localStorage.getItem('satoken')
    const response = await fetch(
      `/api/rag/ask?question=${encodeURIComponent(question)}&sessionId=${sessionId.value}`,
      { headers: { satoken: token } }
    )

    if (!response.ok) throw new Error(`HTTP ${response.status}`)

    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''

    while (true) {
      const { done, value } = await reader.read()
      if (done) break

      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split('\n')
      buffer = lines.pop() || ''

      for (const line of lines) {
        if (line.startsWith('data:')) {
          const text = line.slice(5)
          if (text === '[DONE]') {
            reader.cancel()
            break
          }
          aiMessage.content += text
          scrollToBottom()
        }
      }
    }

    if (!aiMessage.content) {
      aiMessage.content = '（未收到回答）'
    }
  } catch {
    if (aiMessage.content === '') {
      aiMessage.content = '回答失败，请检查后端是否已启动'
      ElMessage.error('请求失败')
    }
  } finally {
    asking.value = false
    scrollToBottom()
  }
}

const scrollToBottom = () => {
  nextTick(() => {
    if (chatBodyRef.value) {
      chatBodyRef.value.scrollTop = chatBodyRef.value.scrollHeight
    }
  })
}
</script>

<template>
  <div>
    <!-- 聊天区域：固定高度 500px -->
    <div ref="chatBodyRef" style="height: 500px; overflow-y: auto; background: #f5f7fa; border-radius: 8px; padding: 20px;">
      <div v-if="messages.length === 0" style="text-align: center; color: #ccc; margin-top: 80px;">
        <el-icon :size="48"><ChatDotRound /></el-icon>
        <p>先在「知识库管理」上传文档，然后在这里提问</p>
      </div>

      <div
        v-for="(msg, index) in messages"
        :key="index"
        :style="{
          display: 'flex',
          justifyContent: msg.role === 'user' ? 'flex-end' : 'flex-start',
          marginBottom: '16px',
        }"
      >
        <div
          :style="{
            maxWidth: '70%',
            padding: '12px 16px',
            borderRadius: '12px',
            lineHeight: '1.6',
            whiteSpace: 'pre-wrap',
            backgroundColor: msg.role === 'user' ? '#409eff' : '#fff',
            color: msg.role === 'user' ? '#fff' : '#333',
            boxShadow: '0 2px 8px rgba(0,0,0,0.08)',
          }"
        >
          {{ msg.content }}
        </div>
      </div>
    </div>

    <!-- 底部操作区：新对话 + 输入框 + 发送 -->
    <div style="display: flex; gap: 12px; margin-top: 16px;">
      <el-button :icon="Plus" @click="newChat" :disabled="asking" title="开始新对话（清空当前记录）" />
      <el-input
        v-model="inputQuestion"
        placeholder="输入你的问题..."
        @keyup.enter="handleAsk"
        :disabled="asking"
      />
      <el-button
        type="primary"
        :icon="Promotion"
        :loading="asking"
        :disabled="!inputQuestion.trim()"
        @click="handleAsk"
      >
        发送
      </el-button>
    </div>
  </div>
</template>
