# Vercel 部署（替代 Netlify）

Netlify 免费版函数上限 10 秒，而商汤这个模型要 7±3 秒，经常超时。
Vercel 不需要实名认证，GitHub 账号直接登录，函数超时可以设到 60 秒。

## 目录结构

```
vercel/
├── api/
│   ├── smart-parse.js   → /api/smart-parse
│   └── example.js       → /api/example
└── vercel.json          → maxDuration: 60
```

## 部署步骤（网页版，不用装任何东西）

1. 打开 <https://vercel.com>，点 **Continue with GitHub** 登录（不需要实名、不需要信用卡）
2. **Add New… → Project**
3. 选择仓库 `SpeakerC/worddrill`（或你的 fork），点 Import
4. 关键：**Root Directory** 填 `vercel`
5. Framework Preset 选 **Other**，Build Command 留空，Output Directory 留空
6. 展开 **Environment Variables**，添加：

   | Name | Value |
   |---|---|
   | `SENSENOVA_API_KEY` | 你的商汤 Key（`sk-` 开头） |

7. 点 **Deploy**，等几十秒

部署完会给你一个域名，形如 `https://worddrill-ai-xxxx.vercel.app`。

## 验证

```powershell
curl.exe -X POST https://你的域名.vercel.app/api/smart-parse `
  -H "content-type: application/json" `
  -d "{\"text\":\"abandon 放弃\"}"
```

返回 `{"ok":true,"markdown":"...","count":1}` 就通了。

## 在 App 里切换

设置 → 智能解析服务地址 → 填：

```
https://你的域名.vercel.app/api/smart-parse
```

例句地址会自动推导成 `/api/example`，不用单独配。

## 好处

- **60 秒超时**：模型 7 秒、20 个词 12 秒都不会被掐断
- 不需要实名、不需要信用卡
- 免费额度：Hobby 计划每月 100 GB-小时 + 100 万次函数调用，个人用绰绰有余

## 注意

- `*.vercel.app` 在国内的可达性和 pages.dev 类似，属于「时好时坏」。如果打不开，可以在
  Vercel 里绑一个自己的域名（项目 → Settings → Domains）
- 环境变量改完要 **Redeploy** 才生效
