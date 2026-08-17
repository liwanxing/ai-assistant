// =====================================================================
// 简单 Markdown 转 HTML
// =====================================================================
// 小程序的 rich-text 组件支持渲染 HTML 字符串
// PC 端用 marked + highlight.js + DOMPurify（完整方案）
// 小程序端用这个轻量方案够用（对话场景不需要复杂排版）

export function renderMarkdown(text) {
  if (!text) return ''
  let html = text

  // 代码块：```lang\n...\n``` → <pre><code>...</code></pre>
  html = html.replace(/```(\w*)\n([\s\S]*?)```/g, (_, lang, code) => {
    const escaped = code.replace(/</g, '&lt;').replace(/>/g, '&gt;')
    return '<pre style="background:#f6f8fa;border-radius:6px;padding:10px;overflow-x:auto;"><code>' + escaped + '</code></pre>'
  })

  // 行内代码：`code` → <code>code</code>
  html = html.replace(/`([^`]+)`/g, '<code style="background:#f0f0f0;padding:2px 5px;border-radius:3px;font-size:0.9em;">$1</code>')

  // 加粗：**text** → <b>text</b>
  html = html.replace(/\*\*(.+?)\*\*/g, '<b>$1</b>')

  // 引用块：> text → blockquote
  html = html.replace(/^>\s?(.+)$/gm, '<blockquote style="border-left:3px solid #409EFF;padding-left:10px;color:#666;">$1</blockquote>')

  // 换行：\n → <br>
  html = html.replace(/\n/g, '<br>')

  return html
}
