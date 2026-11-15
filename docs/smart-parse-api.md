# 智能解析接口（供各版本 / 第三方客户端使用）

把任意词汇文本整理成固定格式的三字段文本行。模型与密钥都在服务端，客户端不需要 Key。

```
客户端 ──POST /api/smart-parse──▶ 边缘节点（EdgeOne）──▶ 商汤日日新 ──▶ 清洗 ──▶ 返回文本行
```

## 基本信息

| 项 | 值 |
|---|---|
| 地址 | `https://api.zzlltt.xyz/api/smart-parse` |
| 方法 | `POST`（`OPTIONS` 用于预检，其他方法返回 405） |
| 请求格式 | `application/json` |
| 响应格式 | `application/json; charset=utf-8` |
| 鉴权 | 无（公开接口，不要在里面放任何私密数据） |
| 回退地址 | `https://worddrill-vercel.vercel.app/api/smart-parse`、`https://worddrill-ai.netlify.app/api/smart-parse`（协议完全一致，按顺序回退） |

> 三个地址的请求/响应完全一致，客户端可以按顺序尝试；也可以只用一个。

## 请求

```http
POST /api/smart-parse HTTP/1.1
Host: api.zzlltt.xyz
Content-Type: application/json

{"text":"abandon 放弃\nability 能力\naccent 口音"}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `text` | string | 是 | 要整理的原文。**单次上限 3000 字**，超了返回 413 |

建议客户端按行切块，**每块 ≤600 字（约 40 个词）**，串行请求再拼起来。块太大模型容易截断，也更慢。

## 响应

### 成功（200）

```json
{
  "ok": true,
  "markdown": "| 英文 | 词性 | 汉语 |\n| --- | --- | --- |\n| abandon | v. | 放弃 |\n| ability | n. | 能力 |\n| accent | n. | 口音 |",
  "count": 3,
  "truncated": false
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `ok` | boolean | 成功为 `true` |
| `markdown` | string | 固定格式文本：第一行表头、第二行分隔线，之后每行一个词条，用 `\|` 分隔三个字段 |
| `count` | number | 实际词条数（= 数据行数，不含表头/分隔线） |
| `truncated` | boolean | 模型输出是否被长度截断（为 `true` 时建议缩小分块重试） |

### 失败

HTTP 状态码非 200，body 形如：

```json
{"ok": false, "error": "具体原因"}
```

| 状态 | 含义 | 处理建议 |
|---|---|---|
| 400 | 请求体不是合法 JSON / `text` 为空 | 检查请求 |
| 405 | 用了 GET 等非 POST 方法 | 改 POST |
| 413 | 单段超过 3000 字 | 分块重发 |
| 422 | 没整理出任何词条 | 内容可能不是词汇表，或换成分段重试 |
| 429 | 模型接口限流 / 额度不足 | 稍后重试，降低并发 |
| 502 | 模型接口报错、超时或返回不合法 | 重试或换回退地址 |

## 解析返回的 `markdown`

返回的是**纯文本行**，不是需要 HTML 渲染的表格。解析规则：

1. 按行切分；
2. 丢掉不含竖线、或竖线少于 2 个的行（模型偶尔会加说明）；
3. 丢掉分隔线（形如 `| --- | --- | --- |`）和表头行（含「英文」和「汉语/中文」）；
4. 剩下的每行去掉首尾竖线后按 `|` 拆分、逐列 `trim`，得到 `英文`、`词性`、`汉语`；
5. 词性可能是空串（词组常见）；同一个词按小写去重。

## 示例

### curl

```bash
curl -X POST "https://api.zzlltt.xyz/api/smart-parse" \
  -H "content-type: application/json" \
  -d '{"text":"abandon 放弃\nability 能力"}'
```

### JavaScript / TypeScript

```ts
async function smartParse(text: string) {
  const res = await fetch('https://api.zzlltt.xyz/api/smart-parse', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ text }),
  });
  const data = await res.json();
  if (!res.ok || !data.ok) throw new Error(data.error || `HTTP ${res.status}`);
  return data as { ok: true; markdown: string; count: number; truncated: boolean };
}
```

### Kotlin（OkHttp / HttpURLConnection 同理）

```kotlin
val body = JSONObject().put("text", text).toString()
val conn = URL("https://api.zzlltt.xyz/api/smart-parse").openConnection() as HttpURLConnection
conn.requestMethod = "POST"
conn.doOutput = true
conn.setRequestProperty("Content-Type", "application/json")
conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
val resp = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
val json = JSONObject(resp)
check(json.optBoolean("ok")) { json.optString("error") }
val markdown = json.getString("markdown")
```

## 超时与重试

- 服务端对上游模型设了 9 秒超时，正常返回通常在 **1–6 秒**；
- 客户端读超时建议 **≥15 秒**，写超时 5 秒；
- 失败按 429 → 稍后重试、502 → 换回退地址 处理；
- 不要并发打同一个账号（免费额度有限流）。

## 隐私

- 只有调用这个接口时，`text` 才会发到边缘节点，再转给商汤日日新模型；
- 服务端不落盘、不写内容日志；
- 不要把密码、个人信息等非词汇内容发过来；
- 公开接口，任何人拿到地址都能用，请不要把它当成私密服务。

## 自建 / 迁移

服务端代码在本仓库：

| 平台 | 文件 | 入口 |
|---|---|---|
| EdgeOne（当前线上） | `edgeone/smart-parse.js` | `addEventListener('fetch')` |
| Netlify | `netlify/functions/smart-parse.js` | `exports.handler` |
| Vercel | `vercel/api/smart-parse.js` | `exports.handler` |
| 阿里云函数计算 | `aliyun/smart-parse.js` | `exports.handler` |
| SCF（未实现） | — | `main_handler` 稍微包一层即可 |

部署自己的实例时，把模型 Key 放在服务端环境变量（EdgeOne 用 `SENSENOVA.API.KEY`，Netlify/Vercel 用 `SENSENOVA_API_KEY`），再把客户端地址换成自己的。