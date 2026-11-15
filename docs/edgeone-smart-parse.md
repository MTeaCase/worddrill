# 智能解析网关：EdgeOne 边缘函数部署

导入页的「智能解析」需要一个服务端转发，模型 Key 不能进 APK。目前有三套实现：

| 目录 | 平台 | 入口写法 |
|---|---|---|
| `netlify/functions/smart-parse.js` | Netlify | `exports.handler` |
| `vercel/api/smart-parse.js` | Vercel | `exports.handler` |
| **`edgeone/smart-parse.js`** | **EdgeOne 边缘函数** | **`addEventListener('fetch')`** |
| `aliyun/smart-parse.js` | 阿里云函数计算 | `exports.handler` |

这份文档只讲 EdgeOne。

## 为什么选 EdgeOne

- 边缘节点执行，离用户近；
- 免费版 0 美元，流量与请求数不限；
- 国际站不需要中国大陆 ICP 备案。

## 先看限制（官方文档）

| 项 | 限制 |
|---|---|
| 语言 | 仅 JavaScript |
| 单次执行 CPU 时间 | **200 ms**（不含等 I/O 的时间） |
| 运行内存 | 128 MB |
| 请求体 | 1 MB |
| 代码包 | 5 MB |
| 每站点函数数 / 触发规则 | 100 / 200 |
| 控制台输出 | 单次最多 20 条 |
| 循环次数 | 单次最多 10 万次 |

免费版额度：

| 项 | Free |
|---|---|
| 流量 / 请求数 | 不限 |
| 边缘函数调用 | 300 万次/月，超出后**规则失效**（不扣费，直接停用） |
| 边缘函数 CPU | 300 万毫秒/月（约 50 分钟） |

关于 CPU：代理的耗时几乎全在等商汤接口返回，CPU 只花在 JSON 解析和字符串处理上，单次远低于 200 ms；真正要留意的是每月 CPU 总量。

## 部署步骤

1. 登录控制台 <https://console.tencentcloud.com/edgeone>，左侧 **服务总览 → 网站安全加速**，点要用的站点。
2. **边缘函数 → 函数管理 → 创建函数**，选「Hello World」模板，函数名填 `worddrill-smart-parse`（只能字母数字连字符，2–30 位，创建后不可改）。
3. 把本仓库 `edgeone/smart-parse.js` 的全部内容粘进代码框，点 **创建并部署**。
4. 在函数详情页底部 **环境变量与密钥 → 快速添加**：

   | 变量名 | 类型 | 值 |
   |---|---|---|
   | `SENSENOVA.API.KEY` | **Secret** | `sk-...`（商汤日日新 Key） |
   | `SENSENOVA.MODEL` | String | 可选，默认 `sensenova-6.8-flash-lite` |
   | `SENSENOVA.BASE.URL` | String | 可选，默认 `https://token.sensenova.cn/v1/chat/completions` |
   | `ALLOWED.ORIGIN` | String | 可选，默认 `*` |

   > **注意**：EdgeOne 的变量名只允许字母、数字和 `@ . - ,`，**不允许下划线**。所以不能叫 `SENSENOVA_API_KEY`，代码里用 `env['SENSENOVA.API.KEY']` 取。
   > 加完变量要点一次 **部署** 才生效。

5. **添加触发规则**：匹配类型选 `URL Path`，运算符 `Is`（等于），值 `/api/smart-parse`。
6. 用函数页给出的默认访问域名自测（见下）。

## 验证

```bash
curl -X POST "https://<你的站点域名>/api/smart-parse" \
  -H "content-type: application/json" \
  -d '{"text":"abandon 放弃\nability 能力"}'
```

期望：

```json
{"ok":true,"markdown":"| 英文 | 词性 | 汉语 |\n| --- | --- | --- |\n| abandon | v. | 放弃 |\n| ability | n. | 能力 |","count":2}
```

失败时返回 `{"ok":false,"error":"原因"}`：

| 状态 | 含义 |
|---|---|
| 400 | 请求体不是 JSON / text 为空 |
| 413 | 单段超过 3000 字 |
| 422 | 没整理出词条 |
| 429 | 模型限流或额度不足 |
| 502 | 模型接口报错、超时或返回不合法 |

## 切到 App

两种方式，选一种：

1. **不改包**：「我的 → 智能解析服务地址」填 `https://<你的站点域名>/api/smart-parse`；
2. **改默认值**：把 `app/build.gradle.kts` 的 `SMART_PARSE_URL` 前置成新地址（保留 Netlify / Vercel 作回退），重新发版。

## 需要留意的

- 边缘函数对**单次总时长**没有在限制表里写明，只写了 CPU 200 ms。先按上面的 curl 实测：如果 5–9 秒的模型调用会被截断，把 `UPSTREAM_TIMEOUT_MS` 调小或改用 SCF（Node 运行时，超时可配 30 秒以上）。
- 免费版超额是「规则失效」，不会自动扣费，但代理会直接不可用；用量大时要么升级套餐，要么保留 Netlify / Vercel 作为回退地址。
- 触发规则只匹配 `/api/smart-parse` 这一个路径，不会影响站点上的其他资源。