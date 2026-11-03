# 迁移到阿里云

把三块托管都搬到阿里云，都不需要支付宝——个人实名用「身份证 + 人脸识别」即可。

| 原来 | 阿里云 | 用途 |
|---|---|---|
| Cloudflare Pages | **OSS 对象存储** | 放 `update.json` |
| Gitee Release | **同一个 OSS 桶** | 放 APK |
| Vercel | **函数计算 FC** | 两个 AI 接口 |

## 一、账号与实名

1. 注册 <https://www.aliyun.com>
2. 实名认证：控制台右上角「实名认证」→ 个人 → **身份证 + 人脸识别**（不需要支付宝，也不需要银行卡）
3. 一般几分钟就通过

## 二、OSS：放 update.json 和 APK

1. 控制台搜索 **对象存储 OSS** → 开通（按量付费，没有最低消费）
2. **创建 Bucket**：
   - 名称：`worddrill`（全局唯一，被占用就加后缀）
   - 地域：`华东1（杭州）` 或离你近的
   - 读写权限：**公共读**
   - 其余默认
3. 进入 Bucket → **文件 → 上传文件**，传两个：
   - `update.json`
   - `WordDrill-1.3.3.apk`
4. 点开文件 → 复制「文件 URL」，形如：

```
https://worddrill.oss-cn-hangzhou.aliyuncs.com/update.json
https://worddrill.oss-cn-hangzhou.aliyuncs.com/WordDrill-1.3.3.apk
```

**不需要备案**：用的是 OSS 默认域名。只有绑自己的域名才要备案（要 2~4 周）。

> 默认域名对 apk / json 会加 `Content-Disposition: attachment`，浏览器会变成下载。
> App 里用代码请求，完全不受影响。

### update.json 内容

```json
{
  "versionCode": 12,
  "versionName": "1.4.0",
  "content": "更新说明",
  "url": "https://worddrill.oss-cn-hangzhou.aliyuncs.com/WordDrill-1.4.0.apk",
  "size": 6725993,
  "md5": "cbd5d1a82606465b86f8b200d6ab0b43"
}
```

## 三、函数计算 FC：两个 AI 接口

1. 控制台搜索 **函数计算 FC** → 开通
2. **创建函数**：
   - 类型：**事件函数**（用 HTTP 触发器）
   - 运行环境：**Node.js 18**
   - 函数名称：`smart-parse`
   - 创建方式：**使用示例代码** → 创建后在**在线代码编辑器**里粘贴 `aliyun/smart-parse.js` 的全部内容
   - 超时时间：**60 秒**（默认 3 秒，一定要改）
   - 内存：512 MB
   - 环境变量：`SENSENOVA_API_KEY` = 你的 `sk-...`
3. **触发器管理 → 创建触发器**：
   - 类型：HTTP 触发器
   - 请求方法：POST
   - 认证方式：**无需认证**
   - 会给你一个地址，形如 `https://smart-parse-xxxxx.cn-hangzhou.fcapp.run`
4. 同样流程再建一个 `example` 函数，粘贴 `aliyun/example.js`

`*.fcapp.run` 是阿里云提供的默认测试域名，**不需要备案**。

### 验证

```powershell
curl.exe -X POST https://smart-parse-xxxx.cn-hangzhou.fcapp.run `
  -H "content-type: application/json" `
  -d "{\"text\":\"abandon 放弃\"}"
```

返回 `{"ok":true,"markdown":"...","count":1}` 就通了。

## 四、改 App 配置

`app/build.gradle.kts`：

```kotlin
buildConfigField("String", "UPDATE_URL",
    "\"https://worddrill.oss-cn-hangzhou.aliyuncs.com/update.json\"")

buildConfigField("String", "SMART_PARSE_URL",
    "\"https://smart-parse-xxxx.cn-hangzhou.fcapp.run\"")
```

注意：例句地址是从智能解析地址推导的（把结尾换成 `/example`），所以两个 FC 函数要放在
**同一个服务下、路径分别为 `/smart-parse` 和 `/example`**，或者告诉我真实地址，我改成显式配置。

改完重新打包发版。

## 五、费用

| 项目 | 价格 | 你的量级 |
|---|---|---|
| OSS 存储 | 约 0.12 元/GB/月 | 几 MB，忽略不计 |
| OSS 外网下行 | 约 0.5 元/GB | 100 次下载 × 6.7MB ≈ 0.34 元 |
| 函数计算 | 每月 100 万次调用免费额度 | 个人用远不够花 |
| 备案 | 不需要（用默认域名） | 0 元 |

一年下来大概几块钱到几十块。

## 六、迁移完的检查清单

- [ ] `update.json` 能直接打开（或能下载）
- [ ] APK 的 URL 能下载，大小和 md5 对得上
- [ ] 两个 FC 接口 `curl` 返回 `ok:true`
- [ ] 新 APK 里 `UPDATE_URL` / `SMART_PARSE_URL` 已改
- [ ] 装新 APK 后「检查更新」和「AI 解析」都能用
