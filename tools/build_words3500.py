#!/usr/bin/env python3
"""离线重建内置词库资产（不进 gradle，跑一次把结果提交进仓库）。

为什么要有这个脚本：
内置「高中 3500」词表里有一批历史坏数据——被上一条释义吞掉的常用词（best / body /
child / man / woman …）、坏词头（`v]`、`afte`、`cance`、`ad.` …）、词性旧写法
（`a.` / `ad.`）。网页版已经修好了，但它的 JSON 只有 w/pos/m，直接拿来覆盖会把安卓
独有的音标、例句、搭配（us/uk/ex/ph）全丢掉。

所以这里以网页版修好的词表为「权威词头 + 词性 + 释义」，按规范化词头从旧资产回填
us/uk/ex/ph；改名走 `app/src/main/assets/word_renames.json`（App 的备份兼容也读这份）。

用法：
    python tools/build_words3500.py --web ../worddrill-web/public/assets
    python tools/build_words3500.py --check-only          # 只校验现有资产

退出码非 0 表示有断言没过，不要提交。
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import unicodedata
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "app" / "src" / "main" / "assets"
RENAMES_FILE = ASSETS / "word_renames.json"

BUILTIN_3500 = ASSETS / "words3500.json"
BUILTIN_CET4 = ASSETS / "words_cet4.json"

FIELDS = ("us", "uk", "ex", "ph")

# 旧词性写法 → 统一写法（与 App 的 Pos.kt 保持一致）
POS_UNITS = {
    "a": "adj", "adj": "adj",
    "ad": "adv", "adv": "adv",
    "n": "n", "v": "v", "vt": "vt", "vi": "vi",
    "prep": "prep", "conj": "conj", "pron": "pron",
    "num": "num", "art": "art", "aux": "aux",
    "int": "int", "interj": "int", "interjection": "int",
    "modal": "modal v",
}

MODAL_V = re.compile(r"^modal\s*v\.?$", re.I)

# 已知坏词头：修完之后一条都不该剩下
BAD_HEADWORDS = {
    "v]", "afte", "cance", "energ", "federa", "kilometr", "woo", "shyv",
    "spaghettiv", "tiresomev", "Tibeta", "ad.", "oˈclock", "does doesnˈt",
    "soft sɔft;", "turning n", "commericia", "roller skatingn",
}

# 修完之后必须能搜到的常用词
MUST_HAVE = [
    "best", "body", "child", "man", "woman", "foot", "leaf", "life", "mouse",
    "sheep", "tooth", "wolf", "goose", "thief", "sort", "after", "either",
]

# may（情态动词）/ May（五月）同形不同义，规范化后撞键是合法的
ALLOWED_DUPLICATE_KEYS = {"may"}

# 修复前（1.6.0 资产）的音标/例句/搭配条数，修完之后只能涨不能跌
BASELINE_COVERAGE = {
    "3500": {"us": 3543, "uk": 3550, "ex": 3487, "ph": 3060},
    "cet4": {"us": 4097, "uk": 4138, "ex": 4166, "ph": 3465},
}


def norm_key(word: str) -> str:
    """规范化词头：全角、大小写、星号、音标撇号、空白。"""
    text = unicodedata.normalize("NFKC", str(word or ""))
    text = text.lower().replace("*", "").replace("ˈ", "'").replace("ˌ", "'")
    return re.sub(r"\s+", " ", text).strip()


def norm_pos(pos: str) -> str:
    """词性归一化；认不出来的写法原样保留（用户导入的中文词性别被吃掉）。"""
    text = str(pos or "").strip()
    if not text:
        return ""
    if MODAL_V.match(text):
        return "modal v."

    units: list[str] = []
    for raw in re.split(r"[&/,]", text):
        key = re.sub(r"\s+", " ", raw.strip().lower().rstrip(".").strip())
        if not key:
            continue
        unit = POS_UNITS.get(key)
        if unit is None:
            return text
        if unit not in units:
            units.append(unit)
    if not units:
        return text
    return "&".join(f"{unit}." for unit in units)


def norm_meaning(meaning: str) -> str:
    """释义里残留的旧词性前缀一并收敛。"""
    text = str(meaning or "")
    text = re.sub(r"(^|[\s,，;；/])a\.", r"\1adj.", text)
    text = re.sub(r"(^|[\s,，;；/])ad\.", r"\1adv.", text)
    text = re.sub(r"\bvnum\.", "num.", text, flags=re.I)
    return text.strip()


def load(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


def write(path: Path, entries) -> None:
    # 与仓库里现有资产格式一致：单行 JSON + 结尾换行
    text = json.dumps(entries, ensure_ascii=False, separators=(",", ":"))
    path.write_text(text + "\n", encoding="utf-8")


def with_rich(entry: dict, source: dict, fields=FIELDS) -> dict:
    for field in fields:
        value = str(source.get(field, "") or "").strip()
        if value:
            entry[field] = value
    return entry


def build_3500(web_entries, old_entries, renames) -> tuple[list, Counter]:
    old_by_key = {}
    for entry in old_entries:
        old_by_key.setdefault(norm_key(entry.get("w", "")), entry)

    # 反向表：新词头 → 旧词头，用来把改名词的音标例句也接过来
    reverse = {norm_key(new): norm_key(old) for old, new in renames.items()}

    out = []
    stats = Counter()
    seen = set()
    for web in web_entries:
        word = str(web.get("w", "")).strip()
        key = norm_key(word)
        if not word or not key:
            stats["skipped_empty"] += 1
            continue
        if key in seen and key not in ALLOWED_DUPLICATE_KEYS:
            raise SystemExit(f"✗ [3500] 新词表里有重复词头：{word}")
        seen.add(key)

        source = old_by_key.get(key)
        if source is not None:
            stats["rich_exact"] += 1
        else:
            old_key = reverse.get(key)
            source = old_by_key.get(old_key) if old_key else None
            if source is not None:
                stats["rich_renamed"] += 1

        pos = norm_pos(web.get("pos", ""))
        if not pos and source is not None:
            pos = norm_pos(source.get("pos", ""))
            if pos:
                stats["pos_from_android"] += 1

        entry = {"w": word, "pos": pos, "m": norm_meaning(web.get("m", ""))}
        if source is not None:
            with_rich(entry, source)
        out.append(entry)

    # 同形不同义（may / May）：富字段只留小写那条，
    # 别把情态动词的搭配（may be=也许）挂到月份上
    for entry in out:
        word = entry["w"]
        if word != word.lower() and norm_key(word) in ALLOWED_DUPLICATE_KEYS:
            for field in FIELDS:
                entry.pop(field, None)

    # 旧表里有、新表里没有、又不是改名来源的词：确实是坏行，丢掉并报出来
    kept_old_keys = {norm_key(e["w"]) for e in out}
    kept_old_keys |= {reverse[k] for k in reverse if k in seen}
    dropped = [e.get("w") for e in old_entries if norm_key(e.get("w", "")) not in kept_old_keys]
    if dropped:
        stats["dropped"] = len(dropped)
        print("  丢弃旧词头：", ", ".join(sorted(dropped)))
    return out, stats


def build_cet4(old_entries) -> tuple[list, Counter]:
    stats = Counter()
    out = []
    for old in old_entries:
        word = str(old.get("w", "")).strip()
        if not word:
            stats["skipped_empty"] += 1
            continue
        entry = {"w": word, "pos": norm_pos(old.get("pos", "")), "m": norm_meaning(old.get("m", ""))}
        with_rich(entry, old)
        out.append(entry)
    return out, stats


def coverage(entries, field: str) -> int:
    return sum(1 for e in entries if str(e.get(field, "") or "").strip())


def verify_3500(entries, baseline) -> list[str]:
    problems = []
    total = len(entries)

    if total != 3846:
        problems.append(f"[3500] 条数应为 3846，实际 {total}")

    heads = {str(e["w"]).strip() for e in entries}
    for bad in sorted(BAD_HEADWORDS):
        if bad in heads:
            problems.append(f"[3500] 坏词头还在：{bad}")
    for word in MUST_HAVE:
        if word.lower() not in {h.lower() for h in heads}:
            problems.append(f"[3500] 缺少常用词：{word}")

    for entry in entries:
        word = entry["w"]
        if word != word.strip():
            problems.append(f"[3500] 词头有首尾空白：{word!r}")
        if "]" in word or "[" in word:
            problems.append(f"[3500] 词头含方括号：{word}")
        if "--" in word or " -" in word or "- " in word:
            problems.append(f"[3500] 连字符写法没修：{word}")
        if re.search(r"[ˈˌ]", word):
            problems.append(f"[3500] 词头含音标撇号：{word}")
        if not re.search(r"[A-Za-z]", word):
            problems.append(f"[3500] 词头没有字母：{word}")
        if not str(entry.get("m", "")).strip():
            problems.append(f"[3500] 释义为空：{word}")

    for entry in entries:
        if re.search(r"(^|&)(a|ad)\.", str(entry.get("pos", ""))):
            problems.append(f"[3500] 词性还有旧写法：{entry['w']} → {entry['pos']}")
        if re.search(r"(^|[\s,，;；/])(a|ad)\.", str(entry.get("m", ""))):
            problems.append(f"[3500] 释义里还有 a./ad.：{entry['w']}")

    dup = [key for key, count in Counter(norm_key(e["w"]) for e in entries).items() if count > 1]
    # may（情态动词）/ May（五月）是合法的同形不同义
    dup = [key for key in dup if key != "may"]
    if dup:
        problems.append(f"[3500] 重复词头：{dup}")

    for field, before in baseline.items():
        after = coverage(entries, field)
        if after < before:
            problems.append(f"[3500] {field} 覆盖率下降：{before} → {after}")
        else:
            print(f"  {field}: 基线 {before} → {after}")
    return problems


def verify_cet4(entries, baseline) -> list[str]:
    problems = []
    if len(entries) != 4543:
        problems.append(f"[CET4] 条数应为 4543，实际 {len(entries)}")
    heads = {str(e["w"]).strip().lower() for e in entries}
    if "reservior" in heads:
        problems.append("[CET4] 网页版那条拼写错误 reservior 被带进来了")
    for entry in entries:
        if re.search(r"(^|&)(a|ad)\.", str(entry.get("pos", ""))):
            problems.append(f"[CET4] 词性还有旧写法：{entry['w']} → {entry['pos']}")
        if re.search(r"(^|[\s,，;；/])(a|ad)\.", str(entry.get("m", ""))):
            problems.append(f"[CET4] 释义里还有 a./ad.：{entry['w']}")
        if not str(entry.get("m", "")).strip():
            problems.append(f"[CET4] 释义为空：{entry['w']}")

    dup = [key for key, count in Counter(norm_key(e["w"]) for e in entries).items() if count > 1]
    if dup:
        problems.append(f"[CET4] 重复词头：{dup}")
    for field, before in baseline.items():
        after = coverage(entries, field)
        if after < before:
            problems.append(f"[CET4] {field} 覆盖率下降：{before} → {after}")
    return problems


def main(argv) -> int:
    parser = argparse.ArgumentParser(description="重建内置词库资产")
    parser.add_argument("--web", type=Path, default=Path("../worddrill-web/public/assets"),
                        help="网页版 public/assets 目录（提供修好的两本词表）")
    parser.add_argument("--check-only", action="store_true", help="只校验现有资产，不写文件")
    args = parser.parse_args(argv)

    renames = load(RENAMES_FILE)
    print(f"改名表：{len(renames)} 条")

    old_3500 = load(BUILTIN_3500)
    old_cet4 = load(BUILTIN_CET4)

    if args.check_only:
        print("只校验现有资产：")
        problems = (verify_3500(old_3500, BASELINE_COVERAGE["3500"])
                    + verify_cet4(old_cet4, BASELINE_COVERAGE["cet4"]))
        if problems:
            for problem in problems:
                print("✗", problem)
            print(f"共 {len(problems)} 个问题")
            return 1
        print("✓ 现有资产没有问题")
        return 0

    web_3500 = args.web / "words3500.json"
    if not web_3500.exists():
        print(f"✗ 找不到网页版词表：{web_3500}（用 --web 指定路径）", file=sys.stderr)
        return 2

    new_3500, stats = build_3500(load(web_3500), old_3500, renames)
    print(f"高中 3500：{len(old_3500)} → {len(new_3500)}（回填 {stats['rich_exact']} 条，"
          f"改名回填 {stats['rich_renamed']} 条，词性借安卓 {stats['pos_from_android']} 条）")

    new_cet4, _ = build_cet4(old_cet4)
    print(f"大学四级：{len(old_cet4)} → {len(new_cet4)}（只统一词性，不引入网页版的脏词条）")

    problems = (verify_3500(new_3500, BASELINE_COVERAGE["3500"])
                + verify_cet4(new_cet4, BASELINE_COVERAGE["cet4"]))
    if problems:
        for problem in problems:
            print("✗", problem)
        print(f"共 {len(problems)} 个问题，未写入")
        return 1

    write(BUILTIN_3500, new_3500)
    write(BUILTIN_CET4, new_cet4)
    print("✓ 已写入两本内置词表")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))