// api/auth.js 的类型声明：TS 导入 JS 模块时读取同目录同名 .d.ts

export interface LoginResult {
  tokenName: string
  tokenValue: string
}

export function login(username: string, password: string): Promise<LoginResult>
export function logout(): Promise<void>
export function getCurrentUser(): Promise<any>
