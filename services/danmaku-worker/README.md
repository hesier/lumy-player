# Lumy 弹弹 Play 代理

Cloudflare Worker 使用服务端凭据请求官方 API。App 只获得代理地址和访问 Token；源码不包含真实代理地址、Token 或 AppSecret。不需要安装运行时依赖，测试使用 Node.js 22 或更新版本。

## 本地配置

从仓库根目录运行：

```sh
node services/danmaku-worker/prepare.mjs
node --test services/danmaku-worker/test/*.test.mjs
```

准备脚本读取 Git 忽略的 `.local/dandanplay.properties` 中的 `DANDANPLAY_APP_ID`、`DANDANPLAY_APP_SECRET`，或读取同名环境变量。首次生成 32 字节随机访问 Token；后续运行复用已保存的 Token，不输出任何凭据值。

自定义 Token 须为 32～256 个可见 ASCII 字符，且不能包含反斜杠，避免 Java Properties 将其作为转义符处理。校验失败时不写入配置；自动生成的十六进制 Token 符合此规则。

生成的文件均在 `.local`，权限为 `600`：

- `.local/danmaku-worker/secrets.json`：官方凭据和 `PROXY_TOKEN`，只交给 Worker。
- `.local/danmaku-worker/wrangler.json`：私有部署配置；实际域名不进入公共配置。
- `.local/danmaku-proxy.properties`：只有配置了代理地址时才生成，包含 App 构建所需的 `DANMAKU_PROXY_URL` 和 `DANMAKU_PROXY_TOKEN`。

在本机私有环境中设置 `DANMAKU_PROXY_URL` 为计划使用的 HTTPS 域名根地址，再运行准备脚本；也可以在上述 App 私有 properties 中填写该地址。脚本配置 Cloudflare Custom Domain，域名需在目标 Cloudflare 账号中可用。未配置地址时不会生成公开路由，App 的官方来源继续显示暂不支持。无需把地址或 Token 填入任何受 Git 跟踪的文件。

App 构建优先读取这两个环境变量，其次读取私有 properties。两项必须同时设置或同时为空。生成的 BuildConfig 只有 Base64 编码配置，不包含明文 URL/Token；**编码不等于加密，APK 中仍能提取地址与 Token**。App 不再构建 `DANDANPLAY_APP_SECRET`，也不自动回退到内置官方密钥。缺少代理配置不影响服务器插件和自定义 API。

GitHub 发布构建在仓库 Settings → Secrets and variables → Actions 配置 `DANMAKU_PROXY_URL` 和 `DANMAKU_PROXY_TOKEN` 两项 Repository Secrets。Token 必须与 Worker 的 `PROXY_TOKEN` 一致；值可从本机私有 properties 获取。Release 工作流只在构建 APK 的步骤注入，普通 CI／PR 构建不读取这些 Secrets。两项都未配置时 App 官方来源不可用；只配置一项会构建失败。App 发布构建不需要官方 AppSecret 或 Cloudflare 部署凭据。

## 部署

准备脚本不登录 Cloudflare、不上传文件，也不执行部署。确认私有配置中的路由和账号后，从生成的私有目录运行 Wrangler，CLI 临时文件也会保留在 Git 忽略范围内：

```sh
cd .local/danmaku-worker
npx wrangler@4.156.2 deploy --config wrangler.json --dry-run
npx wrangler@4.156.2 deploy --config wrangler.json --secrets-file secrets.json
```

第二条命令会发布到 Cloudflare，并把密钥上传为 Secrets。不要将 `secrets.json` 作为普通文件资源发布。部署后重新构建 App，官方来源通过代理访问；来源身份仍为 `official:dandanplay`，已有官方匹配和缓存不会因代理域名或 Token 变化而变成自定义来源。

`namespace_id` 是账号范围的限流命名空间，`1001`／`1002` 应避免与账号其他 Worker 冲突；需要时修改私有配置。再次运行准备脚本会保留私有配置中的 Worker 名称、账号和限流绑定。Cloudflare 限流按节点近似计数，**不是全球严格额度**：当前配置每个客户端 IP 每分钟 60 次、每节点未命中缓存的上游请求每分钟 120 次。共享公网 IP 会共用客户端计数；上线后按实际用户量调整。固定 Token 不证明客户端身份，公开发布仍需监测滥用并按需引入设备注册或用户鉴权。

## 接口与边界

| 接口 | 行为 |
| --- | --- |
| `GET /api/v2/search/anime?keyword=...` | 搜索作品，成功结果缓存 30 分钟 |
| `GET /api/v2/bangumi/{id}` | 详情，成功结果缓存 6 小时 |
| `POST /api/v2/match` | 仅文件名匹配；强制 `fileNameOnly` 和占位哈希，不缓存 |
| `GET /api/v2/comment/{id}?withRelated=true` | 获取整集弹幕，默认包含关联弹幕，不在 Worker 缓存 |

所有接口使用 `Authorization: Bearer <Token>`。鉴权和客户端限流先于缓存查询。上游固定为官方服务，不接受目标网址、登录或发送弹幕接口，不转发客户端认证头。Worker 只向官方 API 发送签名；弹幕跳转只允许官方 `api`／`cas` 数字节点 HTTPS 域名，跨域不携带签名或 Token。搜索和详情缓存仅在当前 Cloudflare 数据中心共享；缓存键不含 Token，业务失败不缓存。

可选 `/match` 的上游 404／405／501 原样保留，客户端据此继续搜索；搜索／详情／弹幕的上游 405／501 仍归类为 502，不扩大可选接口回退范围。

匹配请求最多 4 KiB，搜索／详情／匹配响应最多 512 KiB，弹幕流最多 8 MiB，总上游时限 15 秒。超限流会终止下载，客户端得到读取失败；已知响应长度超限则直接返回错误。仅转发 JSON 内容，不记录请求地址、关键词、凭据、原始异常或响应内容。返回给客户端的响应不允许共享缓存，关闭 Worker 请求日志采集。生产环境不启用公开 CORS。

本地自动测试覆盖鉴权、签名、参数白名单、缓存隔离、限流、跳转、超限和错误脱敏。Node 测试不等于 Cloudflare 运行时或国内链路验证；发布前还需验证部署 Secrets、真实限流绑定、手机／电视网络以及搜索→选集→弹幕链路。App 的候选校验、用户纠错和渲染仍在客户端执行。

GitHub CI 的独立 `Worker tests` 任务使用 Node 24，按现有 CI 规则在 dev 推送和 PR 时运行，与 Android 构建并行。它执行 `node --test services/danmaku-worker/test/*.test.mjs`，包含配置准备、参数回退及安全边界回归。测试使用模拟上游和临时 Git 仓库，不读取真实密钥，不请求官方 API，也不部署 Worker。失败会显示独立检查结果；将该检查设为分支保护的必需项后，才能强制阻止失败的 PR 合并。

## 验证状态

当前本地版本：16 项 Node 测试、YAML 校验、文档链接检查、敏感信息扫描和 Wrangler dry-run 通过。已修复可选匹配状态回退及反斜杠 Token 问题，并配置 GitHub CI；首次 CI 运行尚未验证，本轮修复尚未重新部署到云端。

首次云端部署记录（2026-11-03）：三项凭据均经确认为 `secret_text`，自定义域名与证书绑定正确，公共 DNS 和开启证书验证的 HTTPS 请求通过。未授权／未开放路由分别返回 401／404，搜索、详情、11,172 条弹幕下载及文件名匹配成功。测试通过公共 DNS 返回的地址连接，以避开当时测试机未更新的系统解析；未修改系统 DNS 或关闭 TLS 校验。对应 Debug／Release APK 已重新构建，尚未安装到设备。该记录不代表本轮修复已在云端生效。实际域名、账号、限流命名空间和验证明细仅保存在 `.local`。
