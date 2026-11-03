# 用阿里云 ESA「函数和 Pages」托管 update.json

替代 Cloudflare Pages。静态文件不计超时，10 万次/月免费额度，国内节点比 `pages.dev` 稳。

## 仓库里已经准备好的东西

```
pages/
├── update.json   ← App 拉取的更新清单
└── index.html    ← 一个说明页，打开域名时显示
```

**以后每次发版只改 `pages/update.json` 一个文件**，提交后 ESA 会自动重新部署。

## 部署步骤

1. 控制台搜索 **边缘安全加速 ESA** → 开通（有免费额度）
2. 进入 **函数和 Pages → 创建项目**
3. 选择 **导入 Git 仓库** → 授权 GitHub → 选 `MTeaCase/worddrill`（或你的 fork）
4. 构建配置：

   | 项 | 值 |
   |---|---|
   | 构建命令 | **留空**（不需要构建） |
   | 输出目录 | `pages` |
   | 生产分支 | `main` |

5. 创建完成后，在项目里 **绑定自定义域名**：填一个已备案的子域名，例如 `dl.你的域名`
   （按提示去 DNS 加 CNAME / TXT 记录）
6. 等证书签发完成（一般几分钟）

## 验证

```
https://dl.你的域名/update.json
```

浏览器能直接看到 JSON，或者下载下来，都算正常。

## 在 App 里切换

`app/build.gradle.kts`：

```kotlin
buildConfigField(
    "String",
    "UPDATE_URL",
    "\"https://dl.你的域名/update.json\"",
)
```

想留个备用源就写成逗号分隔，按顺序回退：

```kotlin
"\"https://dl.你的域名/update.json,https://worddrill-update.pages.dev/update.json\""
```

## 注意

- ESA Pages **单个文件上限 25 MB**，APK 6.7 MB 其实也能放，但 APK 继续放 Gitee 更快，不建议动
- 免费额度按**请求数**算（10 万次/月），一次检查更新算 1 次，个人用远不到
- 绑定的域名必须**已备案**，且已经接入 ESA
