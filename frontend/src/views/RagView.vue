<script setup>
import { ref, reactive, nextTick } from 'vue'
import { ElMessage } from 'element-plus'
import { ChatDotRound, Promotion } from '@element-plus/icons-vue'
import request from '../utils/request'

// ──────────────────────────────────────
// 数据定义
// ──────────────────────────────────────

// 对话记录：每条消息有 role（user/ai）和 content
const messages = ref([])

// 输入框内容
const inputQuestion = ref('')

// AI 回答中状态（禁用发送按钮）
const asking = ref(false)

// 上传中状态
const uploading = ref(false)

// 聊天区域引用，用于自动滚动到底部
const chatBodyRef = ref(null)

// ──────────────────────────────────────
// 业务方法
// ──────────────────────────────────────

// 隐藏的文件输入框引用
const fileInputRef = ref(null)

// 触发文件选择对话框
const triggerFileSelect = () => {
  fileInputRef.value.click()
}

// 文件选择后的处理：POST /rag/upload
// 后端会自动切分文本 → 转向量 → 存入 Milvus
const handleFileChange = async (event) => {
  const file = event.target.files[0]
  if (!file) return

  uploading.value = true
  try {
    const formData = new FormData()
    formData.append('file', file)
    await request.post('/rag/upload', formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
    })
    ElMessage.success('文档上传成功，已切分存入知识库')
  } catch {
    // 错误已在响应拦截器里弹了 ElMessage
  } finally {
    uploading.value = false
    // 清空 input 的值，否则选同一个文件不会触发 change 事件
    event.target.value = ''
  }
}

// 发送问题：GET /rag/ask?question=xxx（SSE 流式输出）
// 不用 axios（axios 不支持流式读取），改用原生 fetch + ReadableStream
// 效果：AI 回答一个字就显示一个字，打字机效果，不用等全部生成完
const handleAsk = async () => {
  const question = inputQuestion.value.trim()
  if (!question) return

  // 先把用户问题加到对话记录
  messages.value.push({ role: 'user', content: question })
  inputQuestion.value = ''
  asking.value = true

  // 添加一条空的 AI 消息，后面逐字往里追加
  const aiMessage = reactive({ role: 'ai', content: '' })
  messages.value.push(aiMessage)

  try {
    // 手动拼 URL（不走 axios 的 baseURL + interceptors）
    // 带上 satoken，和 request.js 的请求拦截器逻辑一致
    const token = localStorage.getItem('satoken')
    const response = await fetch(
      `/api/rag/ask?question=${encodeURIComponent(question)}`,
      { headers: { satoken: token } }
    )

    if (!response.ok) throw new Error(`HTTP ${response.status}`)

    // 用 ReadableStream 逐块读取 SSE 数据
    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''

    while (true) {
      const { done, value } = await reader.read()
      if (done) break

      // 把二进制块解码成文本，追加到缓冲区
      buffer += decoder.decode(value, { stream: true })

      // SSE 格式：每条消息以 data: 开头，换行分隔
      // 按换行分割处理
      const lines = buffer.split('\n')
      // 最后一行可能不完整（还没收到结尾换行），留到下次处理
      buffer = lines.pop() || ''

      for (const line of lines) {
        if (line.startsWith('data:')) {
          // 去掉 data: 前缀，拿到实际内容
          const text = line.slice(5)
          // 后端流结束时发送 [DONE]，收到后主动关闭连接
          if (text === '[DONE]') {
            reader.cancel()
            break
          }
          // 逐字追加到 AI 消息
          aiMessage.content += text
          scrollToBottom()
        }
      }
    }

    // 如果最终内容为空，说明出问题了
    if (!aiMessage.content) {
      aiMessage.content = '（未收到回答）'
    }
  } catch {
    // 回答失败，更新占位消息为错误提示
    if (aiMessage.content === '') {
      aiMessage.content = '回答失败，请检查后端是否已启动'
      ElMessage.error('请求失败')
    }
  } finally {
    asking.value = false
    scrollToBottom()
  }
}

// 滚动到聊天底部（新消息出现时自动滚下去）
const scrollToBottom = () => {
  nextTick(() => {
    if (chatBodyRef.value) {
      chatBodyRef.value.scrollTop = chatBodyRef.value.scrollHeight
    }
  })
}
</script>

<template>
  <!-- 不用 flex 全屏布局：el-main 默认 overflow:auto，
       flex+calc 高度会跟 el-main 的滚动条产生冲突，侧边栏折叠时触发宽度抖动 -->
  <div>
    <!-- 顶部操作栏：上传文档 -->
    <div style="margin-bottom: 16px;">
      <!-- 用原生 input[type=file] 替代 el-upload，避免组件内部行为干扰 -->
      <input
        ref="fileInputRef"
        type="file"
        accept=".txt"
        style="display: none;"
        @change="handleFileChange"
      />
      <el-button type="primary" :loading="uploading" @click="triggerFileSelect">
        上传文档到知识库
      </el-button>
      <span style="margin-left: 12px; color: #999; font-size: 13px;">
        支持 .txt 文件，上传后自动切分并存入向量库
      </span>
    </div>

    <!-- 聊天区域：固定高度 500px，内容多了自己滚动，不影响外层布局 -->
    <div ref="chatBodyRef" style="height: 500px; overflow-y: auto; background: #f5f7fa; border-radius: 8px; padding: 20px;">
      <!-- 空状态提示 -->
      <div v-if="messages.length === 0" style="text-align: center; color: #ccc; margin-top: 80px;">
        <el-icon :size="48"><ChatDotRound /></el-icon>
        <p>上传文档后，开始提问吧</p>
      </div>

      <!-- 对话气泡 -->
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

    <!-- 底部输入区域 -->
    <div style="display: flex; gap: 12px; margin-top: 16px;">
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
