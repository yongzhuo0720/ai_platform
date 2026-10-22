# AI 平台前端部署文档

AI 平台前端是一个 **Vue 3 + Vite + TypeScript** 的纯静态单页应用（SPA），包含**前台门户**（智能问答、能力广场、场景应用、模型库、知识库、接口中心等）与**管理后台**（用户/角色/部门/知识库/Dify 配置/模型配置/系统日志）。

部署的本质只有两件事：**把构建产物放到 Web 服务器上**，并**把 `/ai` 请求反向代理到后端服务**。

---

## 一、基础环境

| 依赖 | 版本要求 | 说明 |
|------|---------|------|
| Node.js | **18 及以上**（推荐 20 LTS 或更高） | 前端运行时，构建打包必需 |
| npm | 随 Node.js 自带 | 包管理器（Node ≥ 18 自带 npm ≥ 9） |

> 部署时使用的 Nginx 是绿色软件，解压即用，**服务器上不需要装 Node.js**——只需要提前在有网环境把包打好、把 Nginx 准备好。

确认环境：

```bash
node -v     # 输出 v18 及以上，如 v20.x
npm -v      # 输出 9 及以上
```

国内网络安装依赖慢时，可先配置镜像源：

```bash
npm config set registry https://registry.npmmirror.com
```

---

## 二、打包编译

```bash
# 进入前端项目根目录
cd frontend

# 首次部署先安装依赖（仓库已带 package-lock.json，请使用 npm，勿混用 pnpm/yarn）
npm install

# 打包（构建过程会自动做 TypeScript 类型检查）
npm run build
```

> 构建产物在 `frontend/aiweb/` 目录（`vite.config.ts` 中 `build.outDir: 'aiweb'`，`base: './'` 支持部署在任意路径）。

打包成功后 `aiweb/` 目录结构大致如下：

```
aiweb/
├── index.html          # 入口页面
└── assets/             # 编译后的 js/css/图片/字体
```

---

## 三、部署（Windows / Nginx 方案）

### 1) 部署架构

```
浏览器（局域网用户）
  │
  └── http://<服务器IP>:80
        ├── /        → 前端静态文件（aiweb/ 目录）
        └── /ai/*    → 反向代理到后端服务（http://127.0.0.1:8507/ai）
```

- 前端页面由 Nginx 直接托管（纯静态，无需后端参与）
- **前端所有业务接口都走 `/ai` 前缀**，Nginx 必须把这个路径代理到后端，否则页面能打开但接口全部失败

### 2) 准备 Nginx

1. 在官网下载 Nginx Windows 版：`https://nginx.org/en/download.html`（选 `nginx/Windows`，如 nginx-1.24.0.zip）
2. 解压后即为绿色版 Nginx，无需安装

### 3) 放置前端文件

把打包产物复制到 Nginx 的 html 目录下：

```bash
# 把构建产物 aiweb 复制为 nginx/web中（目录名以 nginx.conf 中 root 配置为准）
# 结果：nginx/html/dist/index.html
```

### 4) 配置 nginx.conf

用以下内容替换 `nginx/conf/nginx.conf`（**注意按实际后端地址修改**）：

```nginx
nginx.conf配置文档在deploy文件夹中
```

### 5) 部署后的初始化配置

页面能打开后，还有一步必做：**Dify 的 API 地址和密钥保存在后端数据库**，需在管理后台配置，否则智能问答等 AI 功能无法使用：

1. 用管理员账号登录系统
2. 进入 **管理后台 → Dify 配置管理**
3. 分别配置三个模块：**问答聊天对话**、**知识库对话/工作流**、**知识库文件**，填写对应的 Dify API URL（如 `https://xx.xx.xx.xx/v1`）和 API Key
4. 保存后刷新页面生效（前端会自动重新拉取配置）

### 6) 更新发布

```bash
# 1. 重新构建
cd frontend
npm run build

# 2. 用新的 aiweb 内容覆盖 nginx/web/aiweb
# 3. 浏览器 Ctrl+F5 强制刷新（新资源文件名带 hash，旧缓存不影响）
```

---

## 四、二次开发

### 1) 项目结构

```
frontend/
├── index.html                # 入口 HTML
├── vite.config.ts            # Vite 配置（构建输出 aiweb、开发代理、自动导入）
├── package.json              # 依赖与脚本
├── .env.development          # 开发环境变量（跳过登录开关）
├── .env.example              # 环境变量模板
├── public/                   # 静态资源（logo、config.js 等，遗留文件）
└── src/
    ├── main.ts               # 应用入口
    ├── App.vue
    ├── api/                  # 请求层
    │   ├── axios.js          # 平台后端请求封装（Axios，注入 token）
    │   ├── request.ts        # Dify 请求封装（Fetch，Bearer 鉴权 + SSE 流式）
    │   └── *.ts              # 各业务模块接口（user/role/model/知识库…）
    ├── router/index.ts       # 路由 + 登录守卫（Hash 模式）
    ├── stores/               # Pinia 状态（theme、difyConfig、apiDocuments）
    ├── layouts/              # MainLayout 前台布局 / AdminLayout 后台布局
    ├── views/                # 页面
    ├── components/           # 公共组件
    ├── utils/                # 工具函数
    └── styles/               # 全局样式
```

### 2) 启动本地开发   

```bash
cd frontend
npm install        # 首次
npm run dev        # 启动开发服务器，默认端口 5173
```

浏览器访问 `http://localhost:5173`。

- 开发代理（`vite.config.ts` 中配置）：`/ai` → 本地后端 `http://127.0.0.1:8507`，`/v1` → Dify 直连地址
- 本地后端需先启动（`127.0.0.1:8507`），否则 `/ai` 请求失败
- 停止：终端按 `Ctrl + C`

### 3 ) 常用命令

```bash
npm run dev       # 开发模式（端口 5173）
npm run build     # 生产构建（输出 aiweb/，含类型检查）
npm run preview   # 本地预览构建产物
```

---

## 五、常见问题

| 现象 | 排查 |
|------|------|
| 页面能打开，但接口全部失败 | Nginx 未配置 `/ai` 反向代理，或 `proxy_pass` 指向的后端地址/端口不对 |
| 登录后仍跳回登录页 | 后端登录接口返回的 Cookie 未保存成功——前端与后端必须**同域名**（用 Nginx 反代 `/ai` 即可，避免跨域） |
| 后台 403 / 跳回登录 | 缺少 `admin_access` Cookie，或该账号角色无后台权限 |
| 智能问答无回复 | 检查管理后台 → Dify 配置管理，三个模块的 URL 和 Key 是否已配置且可连通 |
| 部署后页面样式/资源 404 | 构建产物是否完整复制；`root` 指向的目录名与实际复制进去的目录名是否一致（本项目构建产物为 `aiweb`，部署时复制为 `html/dist` 或同步修改 `root`） |
| 依赖安装失败 | 使用 `npm install`（仓库带 `package-lock.json`，勿混用 pnpm/yarn）；网络受限环境需配置镜像源或在有网环境安装 |
| 页面空白、浏览器报模块错误 | 构建产物与源码版本不一致，重新执行 `npm run build` 并清理浏览器缓存 |
