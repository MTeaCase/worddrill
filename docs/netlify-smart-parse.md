# 智能解析服务部署（Netlify + 商汤日日新）

导入页的「AI 智能解析」不会直接连大模型，而是先请求一个自建的小服务，由它把内容整理成
`| 英文 | 词性 | 汉语 |` 表格再返回。这样 API Key 只存在服务端，不会被打包进 APK。

```
App ──POST /api/smart-parse──▶ Netlify Function ──▶ token.sensenova.cn（sensenova-6.8-flash-lite）
                                    │
                            清洗成 Markdown 表格
                                    │
App ◀──────{ ok, markdown, count }──┘
```

## 1. 准备 API Key

- 接口地址：`https://token.sensenova.cn/v1/chat/completions`
- 模型：`sensenova-6.8-flash-lite`（小模型，够用且便宜）
- 鉴权：`Authorization: Bearer sk-...`

可用模型可以用 `GET https://token.sensenova.cn/v1/models` 列出来确认。

## 2. 部署到 Netlify

### 方式 A：命令行（推荐）

```bash
npm i -g netlify-cli
netlify login
cd /path/to/worddrill
netlify init                      # Create & configure a new site，站点名建议 worddrill-ai
netlify env:set SENSENOVA_API_KEY "sk-你的key"
netlify deploy --prod
```

`netlify.toml` 已经配好发布目录和函数目录，不用额外指定。

### 方式 B：Git 集成

Netlify 后台 → Add new site → Import an existing project → 选本仓库：

| 设置 | 值 |
|---|---|
| Build command | 留空 |
| Publish directory | `netlify/public` |
| Functions directory | `netlify/functions` |

再到 Site configuration → Environment variables 添加变量，然后 Trigger deploy。

> 注意：网页上"拖拽文件夹"只能部署静态文件，**函数传不上去**，必须走 A 或 B。

## 3. 环境变量

| 变量 | 必填 | 默认值 |
|---|---|---|
| `SENSENOVA_API_KEY` | ✅ | — |
| `SENSENOVA_MODEL` | ❌ | `sensenova-6.8-flash-lite` |
| `SENSENOVA_BASE_URL` | ❌ | `https://token.sensenova.cn/v1/chat/completions` |
| `ALLOWED_ORIGIN` | ❌ | `*` |

改完变量要 **Trigger deploy** 一次才生效。

## 4. 验证

```bash
curl -X POST https://你的站点.netlify.app/api/smart-parse \
  -H "content-type: application/json" \
  -d '{"text":"abandon 放弃\nability 能力"}'
```

期望：

```json
{"ok":true,"markdown":"| 英文 | 词性 | 汉语 |\n| --- | --- | --- |\n| abandon | v. | 放弃 |\n| ability | n. | 能力 |","count":2}
```

失败时返回 `{"ok":false,"error":"具体原因"}`：

| 状态码 | 含义 |
|---|---|
| 400 | 请求体问题 |
| 413 | 这一段太长（>3000 字） |
| 422 | 没整理出词条 |
| 429 | 模型接口限流 / 额度不足 |
| 502 | 模型接口报错或超时 |

## 5. App 端配置

默认地址在 `app/build.gradle.kts` 的 `SMART_PARSE_URL`。但**通常不用重新打包**——
App 的「设置 → 智能解析服务地址」可以直接改，改完立刻生效。

## 6. 为什么要切块

`sensenova-6.8-flash-lite` 默认会先"思考"再回答，几十个词就要十几秒，会直接撞上
Netlify 函数 10 秒超时。所以：

- 服务端显式传 `reasoning_effort: "none"` + `thinking: {"type":"disabled"}` 关掉思考，同样的活从 10.6 秒降到 1.4 秒
- App 端按行把长文本切成 ≤1200 字的小块，顺序请求再拼成一张表，界面上显示"第 2/5 段"
- 服务端单块上限 3000 字，超了返回 413

实测 30 个词约 4.7 秒，100 个词分成 2~3 段也在可接受范围内。

## 7. 隐私

- 只有用户点「AI 智能解析」并确认后，**当次文本**才会发到 Netlify，再转给模型接口
- 服务端不落盘、不写内容日志
- 不点这个按钮，App 不会发出任何内容
