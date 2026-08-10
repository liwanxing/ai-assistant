import axios from 'axios'
import { ElMessage } from 'element-plus'

// Axios = 前端的 HTTP 客户端（类似后端的 RestTemplate）
// 职责：发请求带 token、收响应统一处理错误
//
// 前后端交互流程：
//   前端调用 request.get('/login')
//   → 请求拦截器：自动在 Header 加 satoken
//   → Vite 代理：/api/login 转发到后端 localhost:8080/login
//   → 后端返回：{ code: 200, message: "success", data: {...} }
//   → 响应拦截器：code=200 返回数据，code!=200 显示错误提示
const request = axios.create({
  baseURL: '/api',
  // 30 秒：默认 10 秒太短，PDF 上传 + embedding 处理可能要十几秒
  timeout: 30000,
})

// 请求拦截器：每次请求自动带上 Sa-Token（类似后端的 SaInterceptor）
request.interceptors.request.use(
  (config) => {
    const token = localStorage.getItem('satoken')
    if (token) {
      config.headers['satoken'] = token
    }
    return config
  },
  (error) => Promise.reject(error),
)

// 响应拦截器：统一处理后端返回的 { code, message, data } 格式
// 类似后端的全局异常处理器，在这里统一处理错误
request.interceptors.response.use(
  (response) => {
    const res = response.data
    // code !== 200 表示业务错误
    if (res.code !== 200) {
      ElMessage.error(res.message || '请求失败')
      // 401 = 未登录，清除 token 并跳转登录页
      if (res.code === 401) {
        localStorage.removeItem('satoken')
        window.location.href = '/login'
      }
      return Promise.reject(new Error(res.message || '请求失败'))
    }
    return res
  },
  (error) => {
    ElMessage.error(error.message || '网络异常')
    return Promise.reject(error)
  },
)

export default request
