// ======================================================================
// 对话 API（对标 PC 端 RagView.vue 里的 SSE 流式对话）
//
// PC 端用 EventSource / fetch + ReadableStream 接收 SSE
// 小程序端用 uni.request + createRequestTask 接收流式数据
//
// 后端接口：
//   GET /agent/chat-stream?question=xxx&sessionId=xxx → SSE 流式返回
//   GET /rag/sessions → 会话列表
//   GET /rag/sessions/{sessionId}/messages → 历史消息
//   DELETE /rag/sessions/{sessionId} → 删除会话
// ======================================================================

import { getToken } from './request'

const BASE_URL = 'http://192.168.5.55:8080'

/**
 * 发送对话（SSE 流式）
 *
 * 原理说明：
 *   后端返回 Content-Type: text/event-stream，数据是逐行推送的
 *   PC 端用 EventSource 自动解析 SSE 格式
 *   小程序没有 EventSource，但 uni.request 的 onChunkReceived 回调
 *   可以收到每次推送的数据块，效果一样
 *
 * @param {string} question - 用户问题
 * @param {string} sessionId - 会话 ID（同一个 ID = 续聊）
 * @param {function} onChunk - 每收到一块数据时的回调
 * @param {function} onDone - 对话结束时的回调（收到 [DONE] 标记）
 * @param {function} onError - 出错回调
 * @returns {RequestTask} 可用于取消请求的 task 对象
 */
export function chatStream({ question, sessionId, onChunk, onDone, onError }) {
  const token = getToken()

  const task = uni.request({
    url: `${BASE_URL}/agent/chat-stream`,
    method: 'GET',
    data: { question, sessionId },
    header: {
      'satoken': token,
      'Accept': 'text/event-stream'
    },
    // 关键：开启流式接收
    enableChunked: true,

    // 非流式回调（整个响应完成时触发，我们在这里做收尾）
    success: () => {
      onDone && onDone()
    },
    fail: (err) => {
      onError && onError(err)
    }
  })

  // 流式回调：每次收到数据块时触发
  // 数据格式是 SSE 的 "data: xxx\n\n"，需要去掉前缀
  if (typeof task.onChunkReceived === 'function') {
    task.onChunkReceived((res) => {
      // res.data 是 ArrayBuffer，转成字符串
      const text = new TextDecoder().decode(res.data)
      // 解析 SSE 行：每行以 "data: " 开头
      const lines = text.split('\n')
      for (const line of lines) {
        if (line.startsWith('data: ')) {
          const content = line.slice(6) // 去掉 "data: " 前缀
          if (content === '[DONE]') {
            onDone && onDone()
          } else if (content) {
            onChunk && onChunk(content)
          }
        }
      }
    })
  }

  return task
}

/**
 * 获取会话列表
 * @returns {Promise<Array<{ sessionId, title, activeTime }>>}
 */
export function getSessions() {
  const token = getToken()
  return new Promise((resolve, reject) => {
    uni.request({
      url: `${BASE_URL}/rag/sessions`,
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
 * 获取某个会话的历史消息
 * @param {string} sessionId
 * @returns {Promise<Array<{ role, content }>>}
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
 * @param {string} sessionId
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
