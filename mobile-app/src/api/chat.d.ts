// api/chat.js 的类型声明：TS 导入 JS 模块时读取同目录同名 .d.ts
// （不用 tsconfig allowJs——vue-tsc 1.x 开 allowJs 会卡死）

/** 流式对话参数 */
export interface ChatStreamOptions {
  question: string
  sessionId: string
  onChunk?: (chunk: string) => void
  onDone?: () => void
  onError?: (err: any) => void
}

/** 会话元信息（后端 ChatSession 实体） */
export interface SessionSummary {
  id: number
  sessionId: string
  title: string
  createTime: string
  updateTime: string
}

/** 历史消息（后端把 markdown 图片提取成单独的 imageUrl 字段） */
export interface SessionMessage {
  role: 'user' | 'ai'
  content: string
  imageUrl?: string
}

/** 发送对话（SSE 流式，真机不支持时内部自动降级） */
export function chatStream(options: ChatStreamOptions): any

/** 获取会话列表（按最后活跃时间倒序） */
export function getSessions(): Promise<SessionSummary[]>

/** 获取某个会话的历史消息 */
export function getSessionMessages(sessionId: string): Promise<SessionMessage[]>

/** 删除会话（后端四件套彻底删） */
export function deleteSession(sessionId: string): Promise<void>
