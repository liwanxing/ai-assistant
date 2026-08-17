// ======================================================================
// 记忆管理 API（对标 PC 端 MemoryView.vue）
//
// 后端接口：
//   GET  /memory/list   → 查询所有记忆
//   PUT  /memory/{id}   → 修改记忆内容
//   DELETE /memory/{id} → 删除记忆
//
// PC 端用 request.get('/memory/list')
// 小程序端用 request({ url: '/memory/list' })
// 逻辑完全一致，只是函数调用方式不同
// ======================================================================

import { request } from './request'

/**
 * 获取记忆列表
 * @returns {Promise<Array<{ id, content, createTime }>>}
 */
export function getMemoryList() {
  return request({ url: '/memory/list' }).then(res => res.data)
}

/**
 * 修改记忆内容
 * @param {number} id - 记忆 ID
 * @param {string} content - 新内容
 */
export function updateMemory(id, content) {
  return request({
    url: `/memory/${id}`,
    method: 'PUT',
    data: { content }
  })
}

/**
 * 删除记忆
 * @param {number} id - 记忆 ID
 */
export function deleteMemory(id) {
  return request({
    url: `/memory/${id}`,
    method: 'DELETE'
  })
}
