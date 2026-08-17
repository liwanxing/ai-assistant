// ======================================================================
// 认证相关 API（对标 PC 端 LoginView.vue 里的登录逻辑）
//
// PC 端流程：
//   request.post('/login', { username, password })
//   → 响应拦截器拿到 res.data
//   → localStorage.setItem('satoken', res.data.tokenValue)
//   → router.push('/user')
//
// 小程序端流程完全一致，只是存储和跳转 API 不同：
//   request({ url: '/login', method: 'POST', ... })
//   → uni.setStorageSync('satoken', res.data.tokenValue)
//   → uni.switchTab({ url: '/pages/index/index' })
// ======================================================================

import { request, setToken, removeToken } from './request'

/**
 * 登录
 * @param {string} username
 * @param {string} password
 * @returns {Promise<{ tokenName: string, tokenValue: string }>}
 */
export function login(username, password) {
  return request({
    url: '/login',
    method: 'POST',
    data: { username, password },
    noAuth: true  // 登录接口不需要带 Token
  }).then(res => {
    // 保存 Token（和 PC 端 localStorage.setItem('satoken', ...) 一样）
    setToken(res.data.tokenValue)
    return res.data
  })
}

/**
 * 登出
 */
export function logout() {
  return request({ url: '/logout', method: 'GET' }).then(() => {
    removeToken()
  })
}

/**
 * 获取当前用户信息
 */
export function getCurrentUser() {
  return request({ url: '/me', method: 'GET' })
}
