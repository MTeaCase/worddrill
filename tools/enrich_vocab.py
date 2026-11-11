#!/usr/bin/env python3
"""用 KyleBing/english-vocabulary 的 sentence 数据，给内置词库补音标和例句。

数据源：https://github.com/KyleBing/english-vocabulary
        full_line_jsonl/sentence/乱序/{高中,四级}.jsonl
许可：BSD-3-Clause, Copyright (c) 2022-2026 KyleBing

只在本地跑一次，产物提交进 app/src/main/assets/。CI 不执行这个脚本，
所以构建机和手机端都不需要它，也不增加任何依赖。

每条 JSONL 的字段：word / us / uk / translations[{type,translation}]
                    / phrases[{phrase,translation}] / sentences[{sentence,translation}]

输出词条的字段：w / pos / m / us / uk / ex
  ex 沿用 App 里的「英文||中文」格式，只保留一条例句。
"""

import argparse
import json
import os
import subprocess
import sys

POS_MAP = {
    "n": "n.",
    "v": "v.",
    "vt": "vt.",
    "vi": "vi.",
    "adj": "a.",
    "adv": "ad.",
    "prep": "prep.",
    "conj": "conj.",
    "pron": "pron.",
    "num": "num.",
    "art": "art.",
    "int": "int.",
    "aux": "aux.",
    "abbr": "abbr.",
}

MAX_SENSES = 3
MAX_MEANING_CHARS = 90

SOURCES = {
    "words3500.json": "高中.jsonl",
    "words_cet4.json": "四级.jsonl",
}


def clean_ipa(raw):
    """去掉分号写法的变体，把 ASCII 重音符换成 IPA 记号。"""
    if not raw:
        return ""
    value = str(raw).replace("；", ";").split(";")[0].strip().strip("/[] ")
    value = value.replace("'", "ˈ").replace("`", "ˌ")
    return value.strip()


def build_meaning(translations):
    """取前三个义项拼成一句，词性取第一个能映射上的。"""
    if not isinstance(translations, list):
        return "", ""
    pos = ""
    senses = []
    for item in translations:
        if not isinstance(item, dict):
            continue
        text = str(item.get("translation") or "").strip()
        if not text:
            continue
        if not pos:
            pos = POS_MAP.get(str(item.get("type") or "").strip().lower(), "")
        senses.append(text)
        if len(senses) >= MAX_SENSES:
            break
    meaning = "；".join(senses)
    if len(meaning) > MAX_MEANING_CHARS:
        meaning = meaning[:MAX_MEANING_CHARS].rstrip("；，, ") + "…"
    return pos, meaning


def build_example(sentences):
    """挑一条长度适中的双语例句：太长的做成挖空题不好读。"""
    if not isinstance(sentences, list):
        return ""
    candidates = []
    for item in sentences:
        if not isinstance(item, dict):
            continue
        en = " ".join(str(item.get("sentence") or "").split())
        zh = " ".join(str(item.get("translation") or "").split())
        if en and zh:
            candidates.append((en, zh))
    if not candidates:
        return ""
    preferred = [c for c in candidates if 24 <= len(c[0]) <= 140]
    en, zh = (preferred or candidates)[0]
    return en + "||" + zh


def build_phrase(phrases):
    """取第一条搭配，格式「英文=中文」，太长的不收。"""
    if not isinstance(phrases, list):
        return ""
    for item in phrases:
        if not isinstance(item, dict):
            continue
        en = str(item.get("phrase") or "").strip()
        zh = str(item.get("translation") or "").strip()
        if en and zh and len(en) <= 40:
            return en + "=" + " ".join(zh.split())
    return ""


def load_jsonl(path):
    index = {}
    with open(path, "r", encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if not line:
                continue
            try:
                item = json.loads(line)
            except json.JSONDecodeError:
                continue
            word = str(item.get("word") or "").strip().lower()
            if word and word not in index:
                index[word] = item
    return index


def enrich(asset_path, source_index, stats):
    with open(asset_path, "r", encoding="utf-8") as handle:
        entries = json.load(handle)

    out = []
    for entry in entries:
        word = str(entry.get("w") or "")
        source = source_index.get(word.strip().lower())
        row = {"w": word}
        pos = entry.get("pos", "")
        meaning = entry.get("m", "")
        us = uk = ex = ""
        ph = ""
        if source:
            src_pos, src_meaning = build_meaning(source.get("translations"))
            if src_meaning:
                meaning = src_meaning
            # 原有词性是人工整理过的，优先保留；只在它为空时才用数据源的
            if not pos and src_pos:
                pos = src_pos
            us = clean_ipa(source.get("us"))
            uk = clean_ipa(source.get("uk"))
            ex = build_example(source.get("sentences"))
            ph = build_phrase(source.get("phrases"))
        row["pos"] = pos
        row["m"] = meaning
        if us:
            row["us"] = us
            stats["us"] += 1
        if uk:
            row["uk"] = uk
            stats["uk"] += 1
        if ex:
            row["ex"] = ex
            stats["ex"] += 1
        if ph:
            row["ph"] = ph
            stats["ph"] += 1
        stats["total"] += 1
        out.append(row)

    with open(asset_path, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(out, handle, ensure_ascii=False, separators=(",", ":"))
        handle.write("\n")
    return len(out)


def download(name, dest):
    url = (
        "https://raw.githubusercontent.com/KyleBing/english-vocabulary/master/"
        "full_line_jsonl/sentence/%E4%B9%B1%E5%BA%8F/" + name
    )
    if os.path.exists(dest):
        print(f"缓存命中 {dest}")
        return dest
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    print(f"下载 {url}")
    subprocess.run(["curl", "-fsSL", "-o", dest, url], check=True)
    return dest


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--assets", default="app/src/main/assets")
    parser.add_argument("--cache", default=".vocab-cache")
    args = parser.parse_args()

    total_stats = {}
    for asset_name, source_name in SOURCES.items():
        asset_path = os.path.join(args.assets, asset_name)
        if not os.path.exists(asset_path):
            print(f"跳过：{asset_path} 不存在", file=sys.stderr)
            continue
        cache_path = os.path.join(args.cache, source_name)
        download(source_name, cache_path)
        index = load_jsonl(cache_path)
        stats = {"total": 0, "us": 0, "uk": 0, "ex": 0, "ph": 0}
        count = enrich(asset_path, index, stats)
        size = os.path.getsize(asset_path)
        print(
            f"{asset_name}: {count} 词（源命中 {len(index)}）"
            f" 音标美 {stats['us']} / 英 {stats['uk']} / 例句 {stats['ex']}"
            f" / 搭配 {stats['ph']} → {size / 1024:.0f} KB"
        )
        total_stats[asset_name] = (count, size)

    if not total_stats:
        print("没有生成任何词库", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())