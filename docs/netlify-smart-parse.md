# 智能解析服务部署（Netlify + 商汤日日新）

导入页的「AI 智能解析」不会直接连大模型，而是先请求一个自建的小服务，由它把内容整理成
`| 英文 | 词性 | 汉语 |` 表格再返回。这样 API Key 只存在服务端，不会被打包进 APK。

```
App ──POST /api/smart-parse──▶ Netlify Function ──▶ api.sensenova.cn（SenseChat-5）
                                    │
                            清洗成 Markdown 表格
                                    │
App ◀──────{ ok, markdown, count }──┘
```

## 1. 准备商汤 API Key

1. 打开商汤大装置控制台 <https://console.sensecore.cn/aistudio/management/api-key>
2. 新建一个 API Key，复制出来（形如 `sk-...`）

## 2. 部署到 Netlify

### 方式 A：网页拖拽（最快）

1. 打开 <https://app.netlify.com>，**Add new site → Deploy manually**
2. 把仓库里的 `netlify/public` 目录拖进去（先让站点建起来）
3. 进入 **Site configuration → Build & deploy → Build settings**，确认：
   - Base directory：留空（仓库根）
   - Build command：留空
   - Publish directory：`netlify/public`
   - Functions directory：`netlify/functions`

> 拖拽部署只会上传静态文件，不会带函数。函数必须用下面的方式 B / C 部署。

### 方式 B：Netlify CLI（推荐）

```bash
npm i -g netlify-cli
netlify login
cd /path/to/worddrill
netlify init            # 关联或新建站点
netlify deploy --prod   # 会上传 netlify/public 并打包 netlify/functions
```

`netlify.toml` 已经配好 publish 与 functions 目录，不用额外指定。

### 方式 C：Git 集成

在 Netlify 里连上本仓库，构建设置同上（Build command 留空），之后每次 push 自动部署。

## 3. 配置环境变量

**Site configuration → Environment variables → Add a variable**

| 变量 | 必填 | 说明 |
|---|---|---|
| `SENSENOVA_API_KEY` | ✅ | 商汤 API Key |
| `SENSENOVA_MODEL` | ❌ | 默认 `SenseChat-5`，想省钱可改 `SenseChat-Turbo` |
| `ALLOWED_ORIGIN` | ❌ | CORS 白名单，默认 `*`（App 请求不受 CORS 限制） |

改完变量要 **Trigger deploy** 一次才生效。

## 4. 验证

```bash
curl -X POST https://你的站点.netlify.app/api/smart-parse \
  -H 'content-type: application/json' \
  -d '{"text":"abandon 放弃\nability 能力"}'
```

期望：

```json
{"ok":true,"markdown":"| 英文 | 词性 | 汉语 |\n| --- | --- | --- |\n| abandon | v. | 放弃 |\n| ability | n. | 能力 |","count":2}
```

失败时返回 `{"ok":false,"error":"具体原因"}`，HTTP 状态码对应：
`400` 参数问题、`413` 内容过长、`422` 没整理出词条、`502` 商汤接口异常。

## 5. App 端配置

内置默认地址写在 `app/build.gradle.kts` 的 `SMART_PARSE_URL`：

```kotlin
buildConfigField("String", "SMART_PARSE_URL", "\"https://你的站点.netlify.app/api/smart-parse\"")
```

**通常不用重新打包**——App 里「设置 → 智能解析服务地址」可以直接改，存在本机
SharedPreferences 里，改完立刻生效。

## 限制与开销

- 单次输入上限 12000 字符（约 3000 个词条），超了会返回 413，建议分批
- Netlify Functions 免费额度：每月 12.5 万次调用、100 小时运行时间，个人用远远够
- 商汤按 token 计费，SenseChat-5 输入 0.008 元/千 tokens；整理 100 个词大约几分钱
- 函数默认 10 秒超时上限，Netlify 免费版可到 10s，长文本可能超时——所以限制在 12000 字

## 隐私

- 用户点「AI 智能解析」并确认后，**当次文本**才会发到 Netlify，再转给商汤
- 服务端不做持久化，不写日志（Netlify 函数日志只记录调用元信息）
- 不点这个按钮，App 不会发出任何内容；日常使用仍然是离线的
