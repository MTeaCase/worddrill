# 单词练习 WordDrill

Android 单词练习 App：**英译汉 / 汉译英 / 单词默写 / 听音拼写**四种模式，内置高中 3500 词，也可以导入自己的词汇表。
无广告、无账号，词库与进度全部保存在本机；只有「检查更新」和「AI 智能解析 / AI 例句」会联网。

**当前版本 1.4.4**（versionCode 18） · 隐私说明见 [PRIVACY.md](PRIVACY.md)

目前1.0版本已经部署到web： [1.0](https://wordrill.zzlltt.xyz/) ，1.2移植工作紧张进行中。

Kotlin · 原生 View · SQLite · Material 3 · minSdk 24 / targetSdk 36

## 截图

| 练习 | 复习 |
|---|---|
| ![练习](screenshots/tab_practice.png) | ![复习](screenshots/tab_review.png) |

| 词库 | 我的 |
|---|---|
| ![词库](screenshots/tab_library.png) | ![我的](screenshots/tab_mine.png) |

| 英译汉 | 单词默写 |
|---|---|
| ![英译汉](screenshots/practice.png) | ![默写](screenshots/practice_spell.png) |

| 练习结果 | 词表 |
|---|---|
| ![结果](screenshots/result_perfect.png) | ![词表](screenshots/word_list.png) |

| 导入词汇表 | 关于 |
|---|---|
| ![导入](screenshots/import.png) | ![关于](screenshots/about.png) |

> 截图由 Robolectric 原生渲染生成，与真机显示基本一致；只在大版本更新（如 1.3 → 1.4）时重新生成。

## 功能

- **三种模式**：英译汉（看词选义）、汉译英（看义选词）、单词默写（看义拼写，带首字母与字母数提示）、听音拼写（听发音写单词）。
- **每日复习提醒**：到点提醒你复习今天到期的单词，点通知直接开练；时间可自定。
- **掌握度统计**：把词库分成「没学过 / 学习中 / 已掌握 / 易忘」四档，并列出最该复习的错词。
- **已学过**：单独一页看当前词库练过的词，可按「还没掌握 / 已掌握 / 易忘」筛选，行内显示练过几次和下次复习时间。
- **深色模式**：跟随系统深浅色。
- **快捷方式**：长按桌面图标可直接进「今日复习」「词典」「学习报告」。
- **手势**：练习页左滑等于「我不会」，长按加星。
- **词典**：中英文双向模糊搜索，可一键发音或直接练这个词；开启「AI 例句生成」后（默认关闭），打开没有例句的词会自动生成并缓存在本地。
- **发音**：练习页答题后自动朗读单词，词典里可手动点读（系统 TTS，全离线）。
- **底部导航**：界面分成「练习 / 复习 / 词库 / 我的」四个页签，不再把所有东西堆在首页；切页签保留各自的滚动位置和输入。
- **间隔重复复习**：每答一题自动排下次复习时间（1 → 2 → 4 → 7 → 15 → 30 → 60 天），答错归零、第二天再来；「复习」页按**当前词库**显示今日到期待复习数量，可选题量后一键复习，答错的词本轮末尾还会再出现一次。
- **打卡与目标**：每日目标题量、连续打卡天数、近 7 天练习量柱状图、未来 7 天复习量预测（学习报告页）。
- **桌面小组件**：显示今日进度与连续打卡天数，点击进入首页。
- **抽题设置**：每轮 1–200 题，可按词库索引区间抽题，可开关「允许重复抽到已练过的词」。
- **内置词库**：高中 3500 词（3793 条）+ 大学英语四级（4543 条），含词性与释义；默认使用高中 3500 词。
- **自组词库**：直接选择 Markdown 文件导入（自动识别 UTF-8 / GBK），也可粘贴表格；导入后可继续追加单词。
- **AI 智能解析**（1.2.1 起）：导入页可把随便一段文字（单词列表、CSV、OCR 结果、带音标的词汇表）交给自建 AI 服务
  整理成标准表格再导入。服务端是 `netlify/functions/smart-parse.js`，背后接商汤日日新，
  部署方式见 [docs/netlify-smart-parse.md](docs/netlify-smart-parse.md)。
- **屏蔽单词**：长按词表中的单词即可屏蔽，被屏蔽的词不参与抽题，剩余单词索引自动重排，可随时恢复。
- **星标与错题**：练习页、词表都能加星标；答错自动进错题本，**连续答对 2 次**才移出；错题本支持搜索、按「最近错 / 错得最多 / 最该复习」排序、长按手动移出、一键清空。
- **练习进度**：练习页顶部显示当前词库的已练 / 总数与进度条。
- **备份与恢复**：导出 JSON（自组词库、错题、星标、打卡、练习进度、复习排期），导入时与本机数据合并，不覆盖已有内容。
- **应用内更新**：启动时自动检查，有新版可在应用内下载并安装（Android 8+ 会引导授权）。

## 构建

需要 JDK 17+ 与 Android SDK（platform 36）。

```bash
git clone https://github.com/SpeakerC/worddrill.git
cd worddrill
./gradlew assembleDebug        # Windows 用 gradlew.bat assembleDebug

adb install -r app/build/outputs/apk/debug/app-debug.apk
```

APK 也可以直接从 [Releases](https://github.com/SpeakerC/worddrill/releases) 下载。

## 测试

```bash
./gradlew testDebugUnitTest
```

单元测试覆盖：出题逻辑、SQLite 数据层（Robolectric 实际运行）、Markdown 词表解析与编码识别、
间隔重复曲线、复习队列、词典搜索、每日统计、备份合并、更新清单解析、智能解析返回值清洗、界面渲染。

## 隐私

- 不注册、不收集个人信息；词库、错题、打卡、进度只存在本机，卸载即删除
- 联网只有三处：检查更新、下载更新包、你主动使用「AI 智能解析」
- AI 解析只在你点击并确认后发送当次文本，服务端不保存；不用它时 App 完全离线可用
- 权限只有 `INTERNET` 和 `REQUEST_INSTALL_PACKAGES`，没有存储、位置、相机、通讯录、通知权限

完整说明（含第三方服务、代码审计入口）见 **[PRIVACY.md](PRIVACY.md)**。

## 协议

本项目以 [GNU General Public License v3.0](LICENSE) 发布，Copyright (C) 2026 2816 。

发布出去的 APK 对应的完整源代码即本仓库，可按 GPLv3 自由使用、修改、再分发。

内置词库来自 [pluto0x0/word3500](https://github.com/pluto0x0/word3500)（MIT；MIT 与 GPLv3 兼容）
与 [KyleBing/english-vocabulary](https://github.com/KyleBing/english-vocabulary)（BSD 3-Clause；同样与 GPLv3 兼容），
第三方组件与数据说明见 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)。
