// ======================================================================
// HTTP 请求封装（对标 PC 端 frontend/src/utils/request.js）
//
// PC 端用 axios，小程序端用 uni.request，API 不同但思路一样：
//   1. 统一 baseURL（后端地址）
//   2. 请求拦截器：自动带上 satoken
//   3. 响应拦截器：统一处理错误码
//
// 为什么不用 axios？
//   小程序环境没有浏览器的 XMLHttpRequest，uni.request 是小程序原生 API
//   但封装思路和 axios 完全一致
// ======================================================================

// ---- 配置 ----
// 开发阶段：手机和电脑在同一局域网，用电脑的局域网 IP 访问后端
// 生产环境：改成正式的服务器地址
const BASE_URL = 'http://192.168.5.55:8080'

// ---- Token 读写 ----
// PC 端用 localStorage，小程序端用 uni.setStorageSync / getStorageSync
// 两者用法几乎一样，只是函数名不同
const TOKEN_KEY = 'satoken'

export function getToken() {
  return uni.getStorageSync(TOKEN_KEY) || ''
}

export function setToken(token) {
  uni.setStorageSync(TOKEN_KEY, token)
}

export function removeToken() {
  uni.removeStorageSync(TOKEN_KEY)
}

// ---- 核心请求方法 ----
/**
 * request(options) - 统一请求入口
 *
 * @param {string} options.url    - 接口路径，如 '/login'（不含 baseURL）
 * @param {string} options.method - 请求方法，默认 GET
 * @param {object} options.data   - 请求体/查询参数
 * @param {boolean} options.noAuth - 是否跳过 Token（登录接口用）
 *
 * @returns {Promise<object>} 后端返回的 { code, message, data }
 *
 * 使用示例：
 *   const res = await request({ url: '/memory/list' })
 *   const res = await request({ url: '/login', method: 'POST', data: form, noAuth: true })
 */
export function request({ url, method = 'GET', data, noAuth = false }) {
  return new Promise((resolve, reject) => {
    // ---- 构造请求头 ----
    const header = { 'Content-Type': 'application/json' }
    if (!noAuth) {
      const token = getToken()
      if (token) {
        // 和 PC 端一样，Header 名是 satoken
        header['satoken'] = token
      }
    }

    // ---- 发起请求 ----
    uni.request({
      url: BASE_URL + url,
      method,
      data,
      header,
      timeout: 30000,

      // ---- 响应处理（对标 axios 响应拦截器）----
      success: (res) => {
        // HTTP 状态码非 200（网络错误、500 等）
        if (res.statusCode !== 200) {
          const msg = `请求失败 (${res.statusCode})`
          uni.showToast({ title: msg, icon: 'none' })
          reject(new Error(msg))
          return
        }

        const body = res.data

        // 业务码判断（后端返回 { code, message, data }）
        if (body.code !== 200) {
          // 401 = 未登录，清除 Token 跳转登录页
          if (body.code === 401) {
            removeToken()
            uni.reLaunch({ url: '/pages/login/login' })
          }
          uni.showToast({ title: body.message || '请求失败', icon: 'none' })
          reject(new Error(body.message || '请求失败'))
          return
        }

        // 成功：返回整个 body，和 PC 端 interceptor 行为一致
        resolve(body)
      },

      // ---- 网络异常 ----
      fail: (err) => {
        uni.showToast({ title: '网络异常', icon: 'none' })
        reject(err)
      }
    })
  })
}

export default request
