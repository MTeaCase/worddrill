# 第三方组件与数据说明

本项目（WordDrill / 单词练习）以 MIT 协议开源，同时包含下列第三方成果，按各自的协议分发。

## 1. 高中 3500 词库

- 来源：[pluto0x0/word3500](https://github.com/pluto0x0/word3500)
- 协议：MIT License，Copyright (c) 2019 pluto0x0
- 本项目对原始数据做的修改：
  - 把原始词表转换为 JSON（`app/src/main/assets/words3500.json`）；
  - 规范化词头，去掉 `word (过去式, 过去分词)` 这类后缀；
  - 按行拆分词性与释义，整理出 3793 条词条。
- 上游 MIT 协议全文与 [LICENSE](LICENSE) 相同。

## 2. Android 与 Kotlin 组件

以下依赖由 Gradle 引入，均按其各自协议分发：

| 组件 | 协议 |
|---|---|
| AndroidX（core-ktx / appcompat / recyclerview / constraintlayout / activity） | Apache-2.0 |
| Material Components for Android | Apache-2.0 |
| Kotlin 标准库 | Apache-2.0 |
| JUnit 4 | EPL-1.0 |
| Robolectric | MIT |
| AndroidX Test | Apache-2.0 |

完整协议文本见各组件仓库或其 LICENSE 文件。

## 3. 例句语料

`app/src/main/assets/examples.json` 共 3791 条（内置 3793 个词条里仅 2 条异常词条没有例句），由两部分组成：

**3.1 Tatoeba 语料（2864 条）**

- 来源：[Tatoeba](https://tatoeba.org/) 英文—中文（普通话）句对，经 [ManyThings](https://www.manythings.org/anki/) 打包分发
- 协议：CC-BY 2.0 FR（署名要求）
- 本项目做的修改：按词表筛选包含目标单词的短句（6–120 字符），每个单词保留一条；只保留英文原句与中文译文，丢弃句子编号与贡献者信息。

**3.2 模型生成（927 条）**

- 来源：本项目为 Tatoeba 未覆盖的单词，用语言模型生成的简短例句与中文翻译
- 协议：随本项目 MIT 发布
- 说明：句子刻意保持简单、贴近高中水平，可能不如人工例句地道。

> 两种情况都可能存在翻译偏差，仅供参考。

## 4. 图标

`app/src/main/res/mipmap-*`、`drawable/ic_launcher_*`、`drawable/ic_logo.xml` 为本项目自制，
由 `tools/make_icon.py` 与同名矢量图生成，随本项目 MIT 协议发布。
