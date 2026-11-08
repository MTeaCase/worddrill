# 备份文件格式（WordDrill 1.4.1）

导出入口：`我的 → 数据与备份 → 导出备份`
实现：`app/src/main/java/com/worddrill/app/data/WordRepository.kt`（`exportBackupJson` / `importBackupJson`）

文件名：`worddrill-backup-yyyyMMdd-HHmm.json`，编码 UTF-8，无 BOM。

---

## 1. 顶层结构

```json
{
  "format": "worddrill-backup",
  "version": 1,
  "exportedAt": 1730000000000,
  "books": [ ... ],
  "mistakes": [ ... ],
  "starred": [ ... ],
  "daily": [ ... ],
  "review": [ ... ],
  "practice": [ ... ]
}
```

| 字段 | 类型 | 取值 | 说明 |
|---|---|---|---|
| `format` | string | 固定 `"worddrill-backup"` | **导入时强校验**，不一致直接报错 |
| `version` | int | 目前固定 `1` | 导出会写，导入**不校验** |
| `exportedAt` | int64 | 毫秒时间戳 | 导出时刻，仅参考 |
| `books` | array | 见 §2 | **只包含自组词库**（`builtin=0`），内置词库不导出 |
| `mistakes` | array | 见 §3 | 错题 |
| `starred` | array\<string\> | 见 §4 | 星标词名，跨词库 |
| `daily` | array | 见 §5 | 每日打卡 |
| `review` | array | 见 §6 | 复习排期 |
| `practice` | array | 见 §7 | 练习记录 |

除 `format` 外，所有字段在导入时都是**可选**的（缺失即跳过该段）。

---

## 2. books[] —— 自组词库

```json
{
  "name": "我的 CET6",
  "words": [
    {
      "w": "abandon",
      "pos": "v.",
      "m": "放弃；抛弃",
      "star": true,
      "block": false,
      "ex": "He abandoned the plan.||他放弃了这个计划。"
    }
  ]
}
```

| 字段 | 类型 | 取值 | 说明 |
|---|---|---|---|
| `name` | string | 非空 | **词库唯一标识**，导入按名字合并；空串当 `"导入词库"` |
| `words` | array | 见下 | 词条数组，按 `ord` 升序导出 |
| `words[].w` | string | 非空 | 单词原文；空白会被 trim，空则跳过 |
| `words[].pos` | string | 可为空串 | 词性。高中词库形如 `n.` `v.` `adj.` `adv.` `prep.` `pron.` `conj.` `num.` `art.` `int.` `vt.` `vi.` `a.` `ad.`；四级词库为**空串**（词性写在 `m` 里） |
| `words[].m` | string | 可为空 | 中文释义，不含词性前缀时形如 `放弃；抛弃` |
| `words[].star` | bool | `true` / `false` | 星标 |
| `words[].block` | bool | `true` / `false` | 是否屏蔽（屏蔽词不参与练习/list 索引） |
| `words[].ex` | string | 空串或 `英文\|\|中文` | 例句缓存，分隔符是两个竖线 `\|\|`；空串表示无例句 |

**顺序**：导入时**不使用** `ord`，按数组顺序追加到 `MAX(ord)+1`。

---

## 3. mistakes[] —— 错题

```json
{ "w": "abandon", "wrong": 3, "mode": "EN_ZH", "lastAt": 1730000000000 }
```

| 字段 | 类型 | 取值 | 说明 |
|---|---|---|---|
| `w` | string | 词名 | 跨词库按 `lower(word)` 匹配，**不分词库** |
| `wrong` | int | ≥ 1 | 累计答错次数 |
| `mode` | string | `EN_ZH` / `ZH_EN` / `ZH_EN_SPELL` / `LISTEN_SPELL` | 最近一次答错时的题型；缺省 `EN_ZH` |
| `lastAt` | int64 | 毫秒时间戳 | 最近一次答错时间 |

> 注意：App 内部还有 `streak`（连续答对次数，≥2 自动移出错题本），**这个字段没有导出**，导入后恒为 0。

---

## 4. starred[] —— 星标（全局）

```json
["abandon", "ability"]
```

纯词名数组，跨词库。导入时把匹配到的词置星标（只置位、不清除）。

---

## 5. daily[] —— 每日打卡

```json
{ "d": "2026-11-08", "a": 30, "c": 25 }
```

| 字段 | 类型 | 取值 | 说明 |
|---|---|---|---|
| `d` | string | `yyyy-MM-dd` | **本地时区**日期，不是 UTC |
| `a` | int | ≥ 0 | 当天答题数 |
| `c` | int | 0 ≤ c ≤ a | 当天答对数 |

全局统计，不分词库。

---

## 6. review[] —— 复习排期

```json
{ "w": "abandon", "level": 3, "dueAt": 1730000000000, "lastAt": 1730000000000, "lapses": 1 }
```

| 字段 | 类型 | 取值 | 说明 |
|---|---|---|---|
| `w` | string | 词名 | 同上，跨词库匹配 |
| `level` | int | **0–7** | 复习等级；0 = 新词/答错归零，7 = 最高 |
| `dueAt` | int64 | 毫秒时间戳 | 下次到期时间；`dueAt <= now` 即「今天到期」 |
| `lastAt` | int64 | 毫秒时间戳 | 最后一次复习时间 |
| `lapses` | int | ≥ 0 | 答错归零次数（≥2 算「易忘」） |

**间隔表**（`level` = 答对前的等级 → 答对后的间隔）：
`0 → 1 天`、`1 → 2 天`、`2 → 4 天`、`3 → 7 天`、`4 → 15 天`、`5 → 30 天`、`6 → 60 天`、`7 → 60 天`。
答错一律归零到 0 级、固定 1 天后再来。

---

## 7. practice[] —— 练习记录

```json
{ "w": "abandon", "times": 5, "lastAt": 1730000000000 }
```

| 字段 | 类型 | 取值 | 说明 |
|---|---|---|---|
| `w` | string | 词名 | 跨词库匹配 |
| `times` | int | ≥ 1 | 累计答题次数 |
| `lastAt` | int64 | 毫秒时间戳 | 最后一次练习时间 |

「已练 N / M 词」「已学过」页、`不重复抽题` 都依赖这张表。

---

## 8. 导入合并规则（重要）

| 数据 | 合并方式 |
|---|---|
| 词库 | 按 `name` 匹配已存在的词库（**包括内置词库**），同名则并进去，不同名新建 |
| 单词 | 按 `(book_id, lower(word))` 判重，已存在则**不覆盖** `pos` / `m` / `ex` |
| `star` / `starred` | 只置 1，不会清除本机星标 |
| `block` | 只置 1，不会清除本机屏蔽 |
| `mistakes` | 同一词取 `max(wrong)`、`max(lastAt)`，`mode` 用备份里的 |
| `daily` | 同一日期取 `max(answered)`、`max(correct)` |
| `review` | **本机已有排期则整条跳过**（不覆盖） |
| `practice` | 取 `max(times)`、`max(lastAt)` |

一句话：**导入是并集，永远不会因为导入而丢本机数据或降低进度。**

---

## 9. 不进入备份的东西

- 内置词库（高中 3500 词、大学英语四级）的单词本身
- 词库和单词的 `created_at`、`ord` 顺序
- 错题的 `streak`
- App 设置：每日目标、朗读开关、复习提醒时间与开关、AI 例句开关、智能解析地址

---

## 10. 数据库结构（供对照）

`worddrill.db`，VERSION 7。

```sql
books(_id INTEGER PK, name TEXT NOT NULL, builtin INTEGER 0|1, created_at INTEGER)

words(_id INTEGER PK, book_id INTEGER FK→books._id ON DELETE CASCADE,
      ord INTEGER NOT NULL,             -- 词库内 1 起连续序号（屏蔽词不占号）
      word TEXT NOT NULL, pos TEXT DEFAULT '',
      meaning TEXT NOT NULL, starred INTEGER 0|1, blocked INTEGER 0|1,
      example TEXT DEFAULT '',          -- "英文||中文"
      created_at INTEGER)

mistakes(word_id INTEGER PK FK→words._id ON DELETE CASCADE,
         mode TEXT, wrong INTEGER DEFAULT 1, last_at INTEGER,
         streak INTEGER DEFAULT 0)      -- 未导出

practice_log(word_id INTEGER PK FK→words._id ON DELETE CASCADE,
             times INTEGER DEFAULT 1, last_at INTEGER)

daily(date TEXT PK 'yyyy-MM-dd', answered INTEGER, correct INTEGER)

review(word_id INTEGER PK FK→words._id ON DELETE CASCADE,
       level INTEGER 0..7, due_at INTEGER, last_at INTEGER, lapses INTEGER DEFAULT 0)
```

---

## 11. Web 端要互通必须对齐的 7 件事

1. `format` 必须**逐字**等于 `worddrill-backup`，否则 App 直接报「不是 WordDrill 的备份文件」。
2. 所有时间必须是 **epoch 毫秒整数**，不要用 ISO 8601 字符串（`optLong` 会退化成默认值，导致排期全乱）。
3. 日期必须是 `yyyy-MM-dd` 且语义是**本地时区**。
4. 词名匹配大小写不敏感，但**保留原文大小写**写进 `w`。
5. `mode` 用枚举名，不要用中文标签（`英译汉`）。
6. `level` 必须是 0–7 的整数，超出不会自动夹取。
7. Web 导出时**不要包含内置词库**（名字是「高中 3500 词」「大学英语四级」）：App 导入时按名字合并，会把内容灌进内置词库，污染用户的内置词表。

### 已知不对称

| 项 | App → Web | Web → App |
|---|---|---|
| `streak`（连对次数） | 丢失 | 丢失（App 不读） |
| 内置词库 | 不导出 | 若导出会被合进内置库 |
| 词库顺序 | 丢失 | 丢失 |
| 设置项 | 不在备份里 | 不在备份里 |
