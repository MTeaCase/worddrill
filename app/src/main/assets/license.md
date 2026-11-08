# 开放源代码许可

本应用（WordDrill / 单词练习）以 **GNU General Public License v3.0** 发布，
完整协议见仓库根目录的 [LICENSE](../LICENSE) 一节或
<https://www.gnu.org/licenses/gpl-3.0.txt>。简单说：

- 你可以自由使用、修改、再分发本应用；
- 再分发时必须同样以 GPLv3 开放源代码，并保留原作者署名；
- 本应用不提供任何担保。

源代码仓库：
<https://github.com/SpeakerC/worddrill>

## 内置词库

### 高中 3500 词

来自 GitHub 项目 **pluto0x0/word3500**
（<https://github.com/pluto0x0/word3500>，MIT License，Copyright (c) 2019 pluto0x0）。

本项目把它转换成 JSON 并规范化词头、拆分词性与释义，整理出 3793 条词条。
原始数据准确性不做 100% 保证，仅供学习交流使用。

### 大学英语四级（CET-4）

数据来自 GitHub 项目 **KyleBing/english-vocabulary**
（<https://github.com/KyleBing/english-vocabulary>）。

本应用取其中的「四级」词表，按单词去重合并后共 4543 条，
仅保留英文单词与中文释义，未包含例句、音标、词组等字段。

该词库以 BSD 3-Clause 协议授权，原文如下：

```
BSD 3-Clause License

Copyright (c) 2022-2026, KyleBing

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this
   list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

3. Neither the name of the copyright holder nor the names of its
   contributors may be used to endorse or promote products derived from
   this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

## 第三方组件

- **AndroidX / Material Components**：Apache License 2.0
- **Kotlin 标准库**：Apache License 2.0

以上组件均以二进制形式随应用分发，其许可条款随各自的发行包提供。
