# mobile-app

移动端（uni-app + Vue 3），一套代码编译出微信小程序 / H5 / Android/iOS APP，直连后端同一套 API，Sa-Token 账号体系与 Web 端互通。

## 技术栈

- uni-app 3.x（DCloud 的跨端框架，编译到小程序 / H5 / APP 三类平台）
- Vue 3 + Vite + TypeScript
- uni.request（小程序原生请求 API，对标 Web 端的 axios——小程序环境没有 XMLHttpRequest）

## 功能

| 功能 | 说明 |
|------|------|
| 对话 | SSE 流式输出（`enableChunked` 消费 Web 端同一接口）+ Markdown 渲染，真机降级兜底 |
| 登录 | 同一账号体系（satoken Header，与 Web 端完全一致） |
| 记忆管理 | AI 提取的长期记忆，手机端可查可管 |
| 会话历史 | 会话列表、点开续聊、可删除 |

## 开发命令

```bash
npm install                # 安装依赖

npm run dev:mp-weixin      # 微信小程序开发模式（watch 编译，产物在 dist/dev/mp-weixin）
npm run dev:h5             # H5 开发模式（浏览器直接访问，调试最方便）
npm run build:mp-weixin    # 小程序生产构建（产物在 dist/build/mp-weixin）
npm run build:h5           # H5 生产构建
npm run type-check         # TypeScript 类型检查
```

## 微信开发者工具使用步骤

### 前置准备

1. 注册微信小程序账号（[mp.weixin.qq.com](https://mp.weixin.qq.com)），拿到 AppID；只是本地调试的话，用「测试号」也行
2. AppID 已配置在 `src/manifest.json` 的 `mp-weixin.appid`（换成自己的 AppID 也改这里）
3. 下载安装[微信开发者工具](https://developers.weixin.qq.com/miniprogram/dev/devtools/download.html)（稳定版）

### 开发调试

```bash
# 1. 安装依赖并启动小程序编译（watch 模式：改代码自动重新编译）
npm install
npm run dev:mp-weixin
```

2. 打开微信开发者工具 → **导入项目** → 目录选择 **`mobile-app/dist/dev/mp-weixin`**（注意：是编译产物目录，不是 mobile-app 本身），AppID 会自动读取
3. 工具右上角 **详情 → 本地设置 → 勾选「不校验合法域名、web-view（业务域名）、TLS 版本以及 HTTPS 证书」**
   - 本地后端是 `http://局域网IP:8080`，不是 https 备案域名，不勾这个所有请求都会被工具拦截
   - `src/manifest.json` 里的 `mp-weixin.setting.urlCheck: false` 与这个勾选是同一件事的两种配法
4. 之后改代码 → 终端自动重新编译 → 工具自动刷新，体验和 Web 开发的热更新一致

### 真机预览

1. 把 `src/api/request.js` 的 `BASE_URL` 改成电脑的局域网 IP（如 `http://192.168.5.55:8080`）
2. 手机和电脑连同一个 WiFi
3. 工具栏点 **预览** → 生成二维码 → 手机微信扫码即可在真机上运行
4. 真机和模拟器行为不完全一致（见下面「真机踩坑」），上线前务必真机过一遍

### 发布上线（大概流程）

1. `npm run build:mp-weixin` 生产构建（产物在 `dist/build/mp-weixin`）
2. 微信开发者工具导入 `dist/build/mp-weixin` → 点 **上传** → 填版本号和备注
3. 到小程序后台（mp.weixin.qq.com）→ 版本管理 → 提交审核 → 审核通过后发布
4. 注意：正式环境必须使用 https 域名，并在后台「开发管理 → 服务器域名」里配置 request 合法域名

## 目录结构

```
mobile-app/
├── src/
│   ├── pages/                 # 页面（uni-app 的"路由"就是这里的目录结构）
│   │   ├── index/             # 对话页（tabBar 首页）：流式对话 + Markdown
│   │   ├── memory/            # 记忆管理页（tabBar 第二项）
│   │   ├── login/             # 登录页
│   │   └── sessions/          # 会话历史页：列表 / 续聊 / 删除
│   ├── api/                   # 请求封装（对标 PC 端 frontend/src/utils/）
│   │   ├── request.js         # 统一请求入口：baseURL + satoken + 错误码处理
│   │   ├── chat.js            # 对话 SSE 流式 + 会话列表/历史/删除
│   │   ├── auth.js            # 登录
│   │   └── memory.js          # 记忆管理
│   ├── utils/
│   │   └── markdown.js        # 聊天气泡 Markdown 渲染
│   ├── static/                # 图标、logo（tabBar 图标等）
│   ├── App.vue                # 根组件
│   ├── main.ts                # 启动入口
│   ├── pages.json             # 页面路由 + tabBar + 导航栏样式（uni-app 特有）
│   └── manifest.json          # 应用配置：AppID、各平台设置（uni-app 特有）
├── vite.config.ts             # Vite 配置（挂了 uni 插件）
└── package.json               # 依赖与脚本（各平台的 dev/build 命令都在这）
```

## 与后端配合

- 后端接口与 Web 端完全共用，无需任何后端改动：登录 `/login`、对话 `/agent/chat-stream`（SSE）、会话 `/rag/sessions`、记忆 `/memory/*`
- 鉴权同样走 `satoken` 请求头（`src/api/request.js` 统一注入，业务代码无感知）
- 开发阶段：手机/模拟器访问不到 `localhost`，`BASE_URL` 必须写电脑的局域网 IP

## 真机踩坑（模拟器不会暴露）

都在 `src/api/chat.js` 里有注释和兜底实现，这里记结论：

1. **SSE 消费方式**：小程序没有浏览器的 `EventSource`，用 `uni.request` 的 `enableChunked: true` + `task.onChunkReceived` 逐块接收
2. **ArrayBuffer 而非字符串**：`enableChunked` 时 `success` 回调的 `res.data` 是 ArrayBuffer，`JSON.stringify` 只能得到 `"{}"`——表现为"后端有回复、页面不显示"
3. **iOS 旧基础库没有 TextDecoder**：chunk 解码直接抛错，`decodeArrayBuffer` 里做了手动 UTF-8 解码兜底
4. **SSE 行被拆开**：一条 `data:` 行可能跨两个 chunk 到达，用 `lineBuffer` 缓存不完整的行再按 `\n` 切

## 概念说明

> **uni-app** = 写一套 Vue 代码，编译期翻译成各平台的原生代码（小程序的 wxml/wxss、H5 的 html、APP 的原生渲染）
> **pages.json** = uni-app 的路由配置：Web 用 vue-router，uni-app 用它声明页面路径、导航栏、tabBar
> **manifest.json** = 应用身份配置：小程序 AppID、各平台特有设置（这两个文件支持注释，即 JSONC 语法）
> **uni.request** = 小程序原生请求 API，对标 Web 端的 axios；本项目按 axios 拦截器的思路做了统一封装
> **dist/dev 与 dist/build** = 开发产物（watch、含 sourcemap）与生产产物（压缩），分别用于调试和上传发布
