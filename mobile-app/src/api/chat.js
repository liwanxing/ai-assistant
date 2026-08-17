// ======================================================================
// 对话 API
// ======================================================================

import { getToken, BASE_URL } from './request'

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
 *
 * 真机两个坑（模拟器不会暴露）：
 *   1. iOS 低版本基础库没有 TextDecoder，chunk 解码直接抛错 → decodeArrayBuffer 手动 UTF-8 解码兜底
 *   2. enableChunked 时 success 回调的 res.data 是 ArrayBuffer 而非字符串，
 *      JSON.stringify(ArrayBuffer) 会得到 "{}"，导致“后端有回复、页面不显示”
 */
function tryStream({ question, sessionId, token, onChunk, onDone, onError }) {
  let chunkReceived = false
  let doneNotified = false
  let lineBuffer = ''  // SSE 行可能被拆在两个 chunk 里，缓存不完整的行

  const notifyDone = () => {
    if (!doneNotified) {
      doneNotified = true
      onDone && onDone()
    }
  }

  /** 解析一段 SSE 文本（可能含多条 data: 行） */
  const processData = (text) => {
    lineBuffer += text
    const lines = lineBuffer.split('\n')
    lineBuffer = lines.pop() || ''
    for (const line of lines) {
      if (line.startsWith('data:')) {
        const content = line.slice(5).trimStart()
        if (content === '[DONE]') {
          notifyDone()
        } else {
          // 每行补回换行：后端 toSseFlux 按行拆分发送，前端拼回时需要还原
          chunkReceived = true
          onChunk && onChunk(content + '\n')
        }
      }
    }
  }

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
      // 整个过程没收到 chunk（真机 onChunkReceived 不触发）→ 用响应体兑底
      if (!chunkReceived && res.data) {
        if (typeof res.data === 'string') {
          processData(res.data)
        } else if (res.data instanceof ArrayBuffer) {
          processData(decodeArrayBuffer(res.data))
        } else if (res.data.code === 401) {
          // 未登录/过期：后端返回 HTTP 200 + {code:401} 的 JSON
          onError && onError(new Error('登录已过期，请重新登录'))
          return
        }
      }
      notifyDone()
    },
    fail: (err) => {
      console.error('[chat] request failed:', err)
      onError && onError(err)
    }
  })

  if (task && typeof task.onChunkReceived === 'function') {
    task.onChunkReceived((res) => {
      try {
        processData(decodeArrayBuffer(res.data))
      } catch (e) {
        console.error('[chat] chunk decode failed:', e)
      }
    })
  } else {
    console.warn('[chat] onChunkReceived not available, streaming disabled')
  }

  return task
}

/**
 * ArrayBuffer → UTF-8 字符串
 * 优先用原生 TextDecoder（模拟器/新基础库），没有则手动解码（iOS 旧基础库）
 */
function decodeArrayBuffer(buffer) {
  if (typeof TextDecoder !== 'undefined') {
    return new TextDecoder('utf-8').decode(buffer)
  }
  const bytes = new Uint8Array(buffer)
  let str = ''
  for (let i = 0; i < bytes.length; ) {
    const b = bytes[i]
    if (b < 0x80) {           // 1 字节 ASCII
      str += String.fromCharCode(b)
      i += 1
    } else if (b < 0xE0) {    // 2 字节
      str += String.fromCharCode(((b & 0x1F) << 6) | (bytes[i + 1] & 0x3F))
      i += 2
    } else if (b < 0xF0) {    // 3 字节（常见中文都在这里）
      str += String.fromCharCode(((b & 0x0F) << 12) | ((bytes[i + 1] & 0x3F) << 6) | (bytes[i + 2] & 0x3F))
      i += 3
    } else {                  // 4 字节 → 代理对
      const cp = ((b & 0x07) << 18) | ((bytes[i + 1] & 0x3F) << 12) | ((bytes[i + 2] & 0x3F) << 6) | (bytes[i + 3] & 0x3F)
      const offset = cp - 0x10000
      str += String.fromCharCode(0xD800 + (offset >> 10), 0xDC00 + (offset & 0x3FF))
      i += 4
    }
  }
  return str
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
