# 电商订单异常检测 · Vue 前端

Vue 3 + Vite + 原生 JavaScript/CSS 的演示界面：登录、订单概览、异常队列、详情与人工复核、数据质量、模型评估、系统健康。无第三方 UI 库、外部图片/字体。

## 启动

```sh
cd frontend
npm ci
npm run dev
```

`http://127.0.0.1:5173`，`strictPort`；端口占用会退出。构建：`npm run build`，产物 `dist/`。

## 登录与账号

| 用户名 | 角色 | 默认密码 |
|---|---|---|
| `demo_analyst` | 分析员 | `Analyst-Demo-2026!` |
| `demo_reviewer` | 复核员 | `Reviewer-Demo-2026!` |
| `demo_admin` | 管理员 | `Admin-Demo-2026!` |

登录页可一键填入演示账号。账号与数据均为虚构；默认密码仅本机演示，部署时由后端环境变量覆盖，不是生产认证。

## API、CSRF 与权限

- 开发服务器把 `/api` 代理到 `VITE_API_TARGET`（默认 `http://127.0.0.1:8080`），同源 Cookie 生效。
- 写请求先 `GET /api/v2/auth/csrf`，把返回的 `headerName`/`token` 放入请求头；401 会回到登录页，403 显示错误。
- 复核按钮仅 REVIEWER/ADMIN 可见；导入/训练仅 ADMIN。
- 健康页匿名可请求；stub 的 503/DEGRADED 是预期，不当作页面故障。
- 路由为哈希：`#/orders`、`#/alerts`、`#/alerts/:id`、`#/quality`、`#/models`、`#/health`。

## 页面行为

- **订单概览**：分页/状态/关键字；金额为字符串；Olist 订单显示来源与 BRL；不出现“已确认欺诈”。
- **异常队列**：状态/风险筛选；详情显示原因码、模型版本、订单与复核历史。
- **详情复核**：`INCONCLUSIVE→REVIEWING`，`CONFIRMED/REJECTED→RESOLVED`；409 提示刷新；身份由后端会话决定。
- **数据质量**：最近批次、计数、检查；无文件时显示空状态；管理员可导入。
- **模型评估**：模型/评测表；管理员可训练；明确 IEEE-only 与“分不是真实概率”。
- **系统健康**：状态、存储模式、数据库/模型检查；stub 明确 `database=BYPASS`。

## 环境变量

只有 `VITE_` 前缀会进入浏览器构建产物；不要把机密放入。项目根 `.env` 可覆盖：

```dotenv
VITE_API_TARGET=http://127.0.0.1:8080
```

生产需由 Web 服务器/网关提供等价 `/api` 反向代理与 TLS；本前端默认只用于本地。

## 安全与限制

无 `v-html`；令牌不写 localStorage；仅请求相对 `/api`。页面掩码不是加密；前端不是安全边界。构建/浏览器断言见根目录 [verification.md](../docs/verification.md)。
