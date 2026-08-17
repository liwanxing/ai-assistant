// api/memory.js 的类型声明：TS 导入 JS 模块时读取同目录同名 .d.ts

export interface MemoryItem {
  id: number
  content: string
  [key: string]: any
}

export function getMemoryList(): Promise<MemoryItem[]>
export function updateMemory(id: number, content: string): Promise<any>
export function deleteMemory(id: number): Promise<any>
