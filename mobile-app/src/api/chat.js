// ======================================================================
// 对话 API
// ======================================================================

import { getToken } from './request'

const BASE_URL = 'http://192.168.5.55:8080'

/**
 * 发送对话（优先流式，失败自动降级为普通请求）
 */
export function chatStream({ question, sessionId, onChunk, onDone, onError }) {
  const token = getToken()

  // 先尝试流式
  return tryStream({ question, sessionId, token, onChunk, onDone, onError })
}

/**
 * 流式对话
 */
function tryStream({ question, sessionId, token, onChunk, onDone, onError }) {
  let chunkReceived = false

  const task = uni.request({
    url: `${BASE_URL}/agent/chat-stream`,
    method: 'GET',
    data: { question, sessionId },
    header: {
      'satoken': token,
      'Accept': 'text/event-stream'
    },
    enableChunked: true,

    success: (res) => {
      console.log('[chat] request success, statusCode:', res.statusCode)
      // 如果整个过程中没有收到任何 chunk，说明流式失败了
      // 用 success 回调里的数据作为降级
      if (!chunkReceived && res.data) {
        console.log('[chat] no chunks received, falling back to response data')
        const text = typeof res.data === 'string' ? res.data : JSON.stringify(res.data)
        const content = parseSSE(text)
        if (content) {
          onChunk && onChunk(content)
        }
      }
      onDone && onDone()
    },
    fail: (err) => {
      console.error('[chat] request failed:', err)
      onError && onError(err)
    }
  })

  if (task && typeof task.onChunkReceived === 'function') {
    task.onChunkReceived((res) => {
      chunkReceived = true
      const text = new TextDecoder().decode(res.data)
      console.log('[chat] raw chunk:', text)
      console.log('[chat] chunk received, length:', text.length)

      const lines = text.split('\n')
      for (const line of lines) {
        if (line.startsWith('data:')) {
          const content = line.slice(5).trimStart()
          if (content === '[DONE]') {
            onDone && onDone()
          } else if (content) {
            onChunk && onChunk(content)
          }
        }
      }
    })
  } else {
    console.warn('[chat] onChunkReceived not available, streaming disabled')
  }

  return task
}

/**
 * 解析 SSE 文本，提取所有 data 内容
 */
function parseSSE(text) {
  const lines = text.split('\n')
  let result = ''
  for (const line of lines) {
    if (line.startsWith('data:')) {
      const content = line.slice(5).trimStart()
      if (content !== '[DONE]') {
        result += content
      }
    }
  }
  return result
}

/**
 * 获取会话列表
 */
export function getSessions() {
  const token = getToken()
  return new Promise((resolve, reject) => {
    uni.request({
      url: `${BASE_URL}/rag/sessions`,
      method: 'GET',
      header: { 'satoken': token },
      success: (res) => {
        console.log('[sessions] response:', res.data)
        if (res.data.code === 200) {
          resolve(res.data.data)
        } else {
          reject(new Error(res.data.message))
        }
      },
      fail: reject
    })
  })
}

/**
 * 获取某个会话的历史消息
 */
export function getSessionMessages(sessionId) {
  const token = getToken()
  return new Promise((resolve, reject) => {
    uni.request({
      url: `${BASE_URL}/rag/sessions/${sessionId}/messages`,
      method: 'GET',
      header: { 'satoken': token },
      success: (res) => {
        if (res.data.code === 200) {
          resolve(res.data.data)
        } else {
          reject(new Error(res.data.message))
        }
      },
      fail: reject
    })
  })
}

/**
 * 删除会话
 */
export function deleteSession(sessionId) {
  const token = getToken()
  return new Promise((resolve, reject) => {
    uni.request({
      url: `${BASE_URL}/rag/sessions/${sessionId}`,
      method: 'DELETE',
      header: { 'satoken': token },
      success: (res) => {
        if (res.data.code === 200) {
          resolve()
        } else {
          reject(new Error(res.data.message))
        }
      },
      fail: reject
    })
  })
}
