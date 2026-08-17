// api/request.js 的类型声明：TS 导入 JS 模块时读取同目录同名 .d.ts

export const BASE_URL: string

export function getToken(): string
export function setToken(token: string): void
export function removeToken(): void

/** 统一请求入口，返回后端的 { code, message, data } */
export function request(options: {
  url: string
  method?: string
  data?: any
  noAuth?: boolean
}): Promise<any>

export default request
