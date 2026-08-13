<script setup>
import { ref, reactive, nextTick, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ChatDotRound, Promotion, Plus, Delete, Microphone, Picture, Close } from '@element-plus/icons-vue'
import { Marked } from 'marked'
import hljs from 'highlight.js'
import DOMPurify from 'dompurify'
import 'highlight.js/styles/github.css'
import request from '../utils/request'

// ──────────────────────────────────────
// Markdown 渲染：后端直接返回 markdown，前端负责渲染为 HTML
// ──────────────────────────────────────

// 配置 marked：breaks 把单换行转 <br>，gfm 启用 GitHub 风格 markdown
const marked = new Marked({ breaks: true, gfm: true })

// 自定义代码块渲染：集成 highlight.js 语法高亮
marked.use({
  renderer: {
    code({ text, lang }) {
      const language = lang && hljs.getLanguage(lang) ? lang : 'plaintext'
      const highlighted = hljs.highlight(text, { language }).value
      return `<pre><code class="hljs language-${language}">${highlighted}</code></pre>`
    }
  }
})

// 将 markdown 渲染为安全 HTML（DOMPurify 过滤 XSS，防注入）
const renderMarkdown = (content) => {
  if (!content) return ''
  try {
    return DOMPurify.sanitize(marked.parse(content))
  } catch {
    return content
  }
}

// ──────────────────────────────────────
// 数据定义
// ──────────────────────────────────────

// 会话列表（左侧栏展示）
const sessions = ref([])
// 当前选中的会话 ID：续聊的关键——用同一个 sessionId，后端 ChatMemory 自动加载历史上下文
const sessionId = ref(crypto.randomUUID())
// 当前会话的消息列表
const messages = ref([])
const inputQuestion = ref('')
const asking = ref(false)
const chatBodyRef = ref(null)

// ──────────────────────────────────────
// 语音输入：浏览器原生 Web Speech API，零依赖
// 仅 Chrome/Edge 支持，其他浏览器降级为隐藏按钮
// ──────────────────────────────────────
const isListening = ref(false)
let recognition = null
let baseText = ''  // 录音前输入框已有的文字，识别结果追加到这上面

// 检测浏览器是否支持语音识别
const speechSupported = typeof window !== 'undefined' &&
  (window.SpeechRecognition || window.webkitSpeechRecognition)

const initSpeech = () => {
  if (!speechSupported) return
  const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition
  recognition = new SpeechRecognition()
  recognition.lang = 'zh-CN'           // 中文识别
  recognition.interimResults = true     // 返回中间结果，实时显示
  recognition.continuous = false        // 单次模式，用户停顿后自动结束

  // 识别结果回调：interimResults=true 时会多次触发
  recognition.onresult = (event) => {
    let interim = ''
    let final = ''
    for (let i = event.resultIndex; i < event.results.length; i++) {
      if (event.results[i].isFinal) {
        final += event.results[i][0].transcript
      } else {
        interim += event.results[i][0].transcript
      }
    }
    // 最终结果：追加到 baseText；中间结果：baseText + 临时文字实时显示
    if (final) {
      baseText += final
      inputQuestion.value = baseText
    } else if (interim) {
      inputQuestion.value = baseText + interim
    }
  }

  recognition.onerror = (event) => {
    isListening.value = false
    if (event.error !== 'no-speech' && event.error !== 'aborted') {
      ElMessage.error('语音识别失败：' + event.error)
    }
  }

  recognition.onend = () => {
    isListening.value = false
  }
}

const toggleVoice = () => {
  if (!recognition) initSpeech()
  if (!recognition) return

  if (isListening.value) {
    recognition.stop()
    isListening.value = false
  } else {
    // 保存已有文字作为基线，识别结果会追加到后面
    baseText = inputQuestion.value
    recognition.start()
    isListening.value = true
  }
}

// ──────────────────────────────────────
// 图片上传（多模态对话 qwen-vl-plus）
// ──────────────────────────────────────
const selectedImage = ref(null)       // 用户选中的图片文件
const imagePreviewUrl = ref('')       // 本地预览 URL（blob:协议）
const fileInputRef = ref(null)        // 隐藏的 <input type="file"> 引用

const handleImageSelect = (event) => {
  const file = event.target.files[0]
  if (!file) return
  // 前端校验：图片格式 + 10MB 限制
  if (!file.type.startsWith('image/')) {
    ElMessage.error('请选择图片文件')
    return
  }
  if (file.size > 10 * 1024 * 1024) {
    ElMessage.error('图片大小不能超过 10MB')
    return
  }
  selectedImage.value = file
  imagePreviewUrl.value = URL.createObjectURL(file)
  // 重置 input，允许重复选择同一文件
  event.target.value = ''
}

const removeSelectedImage = () => {
  selectedImage.value = null
  if (imagePreviewUrl.value) {
    URL.revokeObjectURL(imagePreviewUrl.value)
    imagePreviewUrl.value = ''
  }
}

// ──────────────────────────────────────
// 页面初始化：加载历史会话列表
// ──────────────────────────────────────
onMounted(() => {
  loadSessions()
})

// 加载会话列表
const loadSessions = async () => {
  try {
    const res = await request.get('/rag/sessions')
    sessions.value = res.data || []
  } catch {
    // 静默失败，不打扰用户
  }
}

// 选中某个历史会话：加载该会话的消息记录，sessionId 保持不变，后续提问自动续上上下文
const selectSession = async (session) => {
  if (asking.value) return
  sessionId.value = session.sessionId
  try {
    const res = await request.get(`/rag/sessions/${session.sessionId}/messages`)
    // 后端返回 { role, content, imageUrl? }，imageUrl 只在图片消息中存在
    messages.value = (res.data || []).map(msg => ({
      role: msg.role,
      content: msg.content,
      imageUrl: msg.imageUrl || null
    }))
    scrollToBottom()
  } catch {
    messages.value = []
  }
}

// 新建对话：换一个新的 sessionId，清空聊天区（不影响历史会话记录）
const newChat = () => {
  if (asking.value) return
  sessionId.value = crypto.randomUUID()
  messages.value = []
}

// 删除会话
const deleteSession = (session) => {
  ElMessageBox.confirm(`确定删除会话「${session.title}」吗？`, '提示', {
    type: 'warning',
    confirmButtonText: '确定',
    cancelButtonText: '取消',
  }).then(async () => {
    try {
      await request.delete(`/rag/sessions/${session.sessionId}`)
      // 删的是当前会话 → 清空聊天区，生成新 sessionId
      if (session.sessionId === sessionId.value) {
        sessionId.value = crypto.randomUUID()
        messages.value = []
      }
      await loadSessions()
      ElMessage.success('删除成功')
    } catch {
      // request 拦截器已处理错误提示
    }
  }).catch(() => {})
}

// ──────────────────────────────────────
// 发送问题：GET /agent/chat?question=xxx（SSE 流式输出）
// 不用 axios（axios 不支持流式读取），改用原生 fetch + ReadableStream
// ──────────────────────────────────────
const handleAsk = async () => {
  const question = inputQuestion.value.trim()
  if (!question && !selectedImage.value) return

  const hasImage = !!selectedImage.value

  // 用户消息加到聊天区（带本地图片预览）
  messages.value.push({
    role: 'user',
    content: question || '(请看图片)',
    imageUrl: imagePreviewUrl.value || undefined
  })
  inputQuestion.value = ''
  asking.value = true

  const aiMessage = reactive({ role: 'ai', content: '' })
  messages.value.push(aiMessage)

  try {
    const token = localStorage.getItem('satoken')
    let response

    if (hasImage) {
      // 有图片：POST FormData 发到多模态接口
      const formData = new FormData()
      formData.append('question', question)
      formData.append('sessionId', sessionId.value)
      formData.append('image', selectedImage.value)
      response = await fetch('/api/agent/chat-with-image', {
        method: 'POST',
        headers: { satoken: token },
        body: formData
      })
    } else {
      // 纯文本：GET URL 参数（原有逻辑）
      response = await fetch(
        `/api/agent/chat?question=${encodeURIComponent(question)}&sessionId=${sessionId.value}`,
        { headers: { satoken: token } }
      )
    }

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
    // 清除已选图片
    if (hasImage) removeSelectedImage()
    asking.value = false
    scrollToBottom()
    await loadSessions()
  }
}

const scrollToBottom = () => {
  nextTick(() => {
    if (chatBodyRef.value) {
      chatBodyRef.value.scrollTop = chatBodyRef.value.scrollHeight
    }
  })
}

// 格式化时间：今天显示时分，其他显示月日
const formatTime = (time) => {
  if (!time) return ''
  const d = new Date(time)
  if (isNaN(d.getTime())) return ''
  const now = new Date()
  if (d.toDateString() === now.toDateString()) {
    return d.toTimeString().slice(0, 5)
  }
  return `${d.getMonth() + 1}/${d.getDate()}`
}
</script>

<template>
  <!-- 整体布局：左侧会话列表 + 右侧聊天区 -->
  <div class="rag-container">
    <!-- ==================== 左侧：历史会话列表 ==================== -->
    <div class="session-sidebar">
      <el-button
        type="primary"
        :icon="Plus"
        @click="newChat"
        :disabled="asking"
        class="new-chat-btn"
      >
        新建对话
      </el-button>

      <div class="session-list">
        <div v-if="sessions.length === 0" class="session-empty">
          <p>还没有对话记录</p>
        </div>

        <div
          v-for="session in sessions"
          :key="session.sessionId"
          class="session-item"
          :class="{ active: session.sessionId === sessionId }"
          @click="selectSession(session)"
        >
          <div class="session-info">
            <div class="session-title">{{ session.title }}</div>
            <div class="session-time">{{ formatTime(session.updateTime) }}</div>
          </div>
          <el-icon
            class="session-delete"
            @click.stop="deleteSession(session)"
          >
            <Delete />
          </el-icon>
        </div>
      </div>
    </div>

    <!-- ==================== 右侧：聊天区 ==================== -->
    <div class="chat-area">
      <!-- 消息区域 -->
      <div ref="chatBodyRef" class="chat-body">
        <div v-if="messages.length === 0" class="chat-empty">
          <el-icon :size="48"><ChatDotRound /></el-icon>
          <p>问我任何问题：知识库内容、查时间、闲聊都行</p>
        </div>

        <div
          v-for="(msg, index) in messages"
          :key="index"
          class="msg-row"
          :class="msg.role === 'user' ? 'msg-user' : 'msg-ai'"
        >
          <!-- AI 消息：后端返回 markdown，渲染为 HTML -->
          <div
            v-if="msg.role === 'ai'"
            class="msg-bubble bubble-ai markdown-body"
            v-html="renderMarkdown(msg.content)"
          ></div>
          <!-- 用户消息：可能带图片 -->
          <div v-else class="msg-bubble bubble-user">
            <img v-if="msg.imageUrl" :src="msg.imageUrl" class="chat-user-image" />
            <div v-if="msg.content && msg.content !== '(请看图片)'">{{ msg.content }}</div>
          </div>
        </div>
      </div>

      <!-- 底部输入区 -->
      <div class="chat-input-wrapper">
        <!-- 图片预览条 -->
        <div v-if="imagePreviewUrl" class="image-preview-bar">
          <img :src="imagePreviewUrl" class="image-preview-thumb" />
          <span class="image-preview-name">{{ selectedImage?.name }}</span>
          <el-icon class="image-preview-remove" @click="removeSelectedImage"><Close /></el-icon>
        </div>
        <div class="chat-input">
          <!-- 隐藏的文件选择器 -->
          <input
            ref="fileInputRef"
            type="file"
            accept="image/png,image/jpeg,image/gif,image/webp"
            style="display:none"
            @change="handleImageSelect"
          />
          <el-input
            v-model="inputQuestion"
            :placeholder="isListening ? '正在聆听...' : '输入你的问题...'"
            @keyup.enter="handleAsk"
            :disabled="asking"
          />
          <!-- 图片上传按钮 -->
          <el-button
            :icon="Picture"
            @click="fileInputRef?.click()"
            :disabled="asking"
          />
          <!-- 语音输入按钮：仅 Chrome/Edge 显示 -->
          <el-button
            v-if="speechSupported"
            :icon="Microphone"
            :type="isListening ? 'danger' : 'default'"
            :class="{ 'mic-pulse': isListening }"
            @click="toggleVoice"
            :disabled="asking"
          />
          <el-button
            type="primary"
            :icon="Promotion"
            :loading="asking"
            :disabled="!inputQuestion.trim() && !selectedImage"
            @click="handleAsk"
          >
            发送
          </el-button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* 整体容器：左右布局，撑满主内容区高度 */
.rag-container {
  display: flex;
  gap: 16px;
  height: calc(100vh - 180px);
}

/* ===== 左侧会话列表 ===== */
.session-sidebar {
  width: 240px;
  display: flex;
  flex-direction: column;
  border-right: 1px solid #e4e7ed;
  padding-right: 12px;
}

.new-chat-btn {
  margin-bottom: 12px;
}

.session-list {
  flex: 1;
  overflow-y: auto;
}

.session-empty {
  text-align: center;
  color: #c0c4cc;
  margin-top: 40px;
  font-size: 13px;
}

.session-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 12px;
  border-radius: 8px;
  cursor: pointer;
  margin-bottom: 4px;
  transition: background-color 0.2s;
}

.session-item:hover {
  background-color: #f5f7fa;
}

.session-item.active {
  background-color: #ecf5ff;
}

.session-info {
  flex: 1;
  overflow: hidden;
}

.session-title {
  font-size: 13px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  color: #303133;
}

.session-item.active .session-title {
  color: #409eff;
  font-weight: 500;
}

.session-time {
  font-size: 11px;
  color: #c0c4cc;
  margin-top: 2px;
}

.session-delete {
  margin-left: 8px;
  color: #c0c4cc;
  font-size: 14px;
  flex-shrink: 0;
}

.session-delete:hover {
  color: #f56c6c;
}

/* ===== 右侧聊天区 ===== */
.chat-area {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.chat-body {
  flex: 1;
  overflow-y: auto;
  background: #f5f7fa;
  border-radius: 8px;
  padding: 20px;
}

.chat-empty {
  text-align: center;
  color: #c0c4cc;
  margin-top: 80px;
}

.msg-row {
  display: flex;
  margin-bottom: 16px;
}

.msg-user {
  justify-content: flex-end;
}

.msg-ai {
  justify-content: flex-start;
}

.msg-bubble {
  padding: 12px 16px;
  border-radius: 12px;
  line-height: 1.6;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08);
}

.bubble-user {
  max-width: 70%;
  background-color: #409eff;
  color: #fff;
  white-space: pre-wrap;
}

.bubble-ai {
  max-width: 90%;
  background-color: #fff;
  color: #333;
}

/* 用户消息中的图片 */
.chat-user-image {
  max-width: 200px;
  max-height: 200px;
  border-radius: 8px;
  margin-bottom: 4px;
  display: block;
}

/* 图片预览条 */
.image-preview-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 12px;
  background: #f5f7fa;
  border-radius: 8px;
  margin-bottom: 8px;
}

.image-preview-thumb {
  width: 40px;
  height: 40px;
  object-fit: cover;
  border-radius: 4px;
}

.image-preview-name {
  flex: 1;
  font-size: 13px;
  color: #606266;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.image-preview-remove {
  cursor: pointer;
  color: #c0c4cc;
  font-size: 16px;
}

.image-preview-remove:hover {
  color: #f56c6c;
}

.chat-input {
  display: flex;
  gap: 12px;
  margin-top: 12px;
}

/* 语音按钮录音中脉冲动画 */
.mic-pulse {
  animation: mic-pulse 1.2s infinite;
}

@keyframes mic-pulse {
  0% { box-shadow: 0 0 0 0 rgba(245, 108, 108, 0.5); }
  70% { box-shadow: 0 0 0 8px rgba(245, 108, 108, 0); }
  100% { box-shadow: 0 0 0 0 rgba(245, 108, 108, 0); }
}
</style>

<!-- Markdown 渲染样式（非 scoped，用 .markdown-body 前缀限定范围） -->
<style>
.markdown-body {
  font-size: 14px;
  line-height: 1.8;
  word-break: break-word;
}

.markdown-body p {
  margin: 0 0 8px;
}

.markdown-body p:last-child {
  margin-bottom: 0;
}

.markdown-body h1,
.markdown-body h2,
.markdown-body h3,
.markdown-body h4,
.markdown-body h5,
.markdown-body h6 {
  margin: 16px 0 8px;
  font-weight: 600;
}

.markdown-body h1 { font-size: 1.4em; }
.markdown-body h2 { font-size: 1.3em; }
.markdown-body h3 { font-size: 1.2em; }
.markdown-body h4 { font-size: 1.1em; }

.markdown-body ul,
.markdown-body ol {
  margin: 0 0 8px;
  padding-left: 24px;
}

.markdown-body li {
  margin: 4px 0;
}

.markdown-body code {
  background-color: #f0f0f0;
  padding: 2px 6px;
  border-radius: 4px;
  font-size: 0.9em;
  font-family: 'Consolas', 'Monaco', 'Courier New', monospace;
}

.markdown-body pre {
  margin: 0 0 12px;
  border-radius: 8px;
  overflow-x: auto;
  background-color: #f6f8fa;
  max-width: 100%;
}

.markdown-body pre code {
  background: none;
  padding: 12px 16px;
  display: block;
  border-radius: 0;
  font-size: 0.85em;
  line-height: 1.6;
}

.markdown-body blockquote {
  margin: 0 0 8px;
  padding: 8px 12px;
  border-left: 4px solid #409eff;
  background-color: #f5f7fa;
  color: #666;
}

.markdown-body table {
  border-collapse: collapse;
  margin: 0 0 12px;
  width: 100%;
  display: block;
  overflow-x: auto;
  white-space: nowrap;
}

.markdown-body th,
.markdown-body td {
  border: 1px solid #ddd;
  padding: 6px 12px;
  text-align: left;
  white-space: normal;
  min-width: 80px;
}

.markdown-body th {
  background-color: #f5f7fa;
  font-weight: 600;
}

.markdown-body a {
  color: #409eff;
  text-decoration: none;
}

.markdown-body a:hover {
  text-decoration: underline;
}

.markdown-body img {
  max-width: 100%;
}

.markdown-body hr {
  border: none;
  border-top: 1px solid #ddd;
  margin: 12px 0;
}
</style>
