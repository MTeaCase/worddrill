# 第三方组件与数据说明

本项目（WordDrill / 单词练习）以 GNU General Public License v3.0 发布，同时包含下列第三方成果，按各自的协议分发。

## 1. 高中 3500 词库

- 来源：[pluto0x0/word3500](https://github.com/pluto0x0/word3500)
- 协议：MIT License，Copyright (c) 2019 pluto0x0
- 本项目对原始数据做的修改：
  - 把原始词表转换为 JSON（`app/src/main/assets/words3500.json`）；
  - 规范化词头，去掉 `word (过去式, 过去分词)` 这类后缀；
  - 按行拆分词性与释义，整理出 3793 条词条。
- MIT 与 GPLv3 兼容：词库数据本身仍按 MIT 分发，本项目整体按 GPLv3 发布；
  MIT 协议全文见 <https://github.com/pluto0x0/word3500/blob/main/LICENSE>。

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

## 3. 图标

`app/src/main/res/mipmap-*`、`drawable/ic_launcher_*`、`drawable/ic_logo.xml` 为本项目自制，
由 `tools/make_icon.py` 与同名矢量图生成，随本项目 GPLv3 协议发布。
