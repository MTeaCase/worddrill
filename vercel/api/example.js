/**
 * Vercel /api/example
 * 给单词生成英中对照例句。
 *
 * 环境变量：SENSENOVA_API_KEY（必填）、SENSENOVA_MODEL（可选）
 */

const ENDPOINT = process.env.SENSENOVA_BASE_URL || 'https://token.sensenova.cn/v1/chat/completions';

const SYSTEM_PROMPT = `给单词写一句简单英文例句（6–16 词，包含该词或其变形）并翻译成中文。

只输出 JSON，不要解释：{"en":"...","zh":"..."}`;

function pickJson(text) {
  const raw = String(text || '').replace(/```(?:json)?/gi, '');
  const start = raw.indexOf('{');
  const end = raw.lastIndexOf('}');
  if (start < 0 || end <= start) return null;
  try {
    return JSON.parse(raw.slice(start, end + 1));
  } catch {
    return null;
  }
}

export default async function handler(req, res) {
  if (req.method === 'OPTIONS') return res.status(200).json({ ok: true });
  if (req.method !== 'POST') return res.status(405).json({ ok: false, error: '只支持 POST' });

  const apiKey = process.env.SENSENOVA_API_KEY;
  if (!apiKey) return res.status(500).json({ ok: false, error: '没有配置 SENSENOVA_API_KEY' });

  const word = String(req.body?.word || '').trim();
  const meaning = String(req.body?.meaning || '').trim();
  if (!word) return res.status(400).json({ ok: false, error: 'word 不能为空' });
  if (word.length > 60) return res.status(413).json({ ok: false, error: '单词太长' });

  let upstream;
  try {
    upstream = await fetch(ENDPOINT, {
      method: 'POST',
      headers: { Authorization: `Bearer ${apiKey}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({
        model: process.env.SENSENOVA_MODEL || 'sensenova-6.8-flash-lite',
        messages: [
          { role: 'system', content: SYSTEM_PROMPT },
          { role: 'user', content: meaning ? `${word}（${meaning}）` : word },
        ],
        temperature: 0.6,
        max_tokens: 400,
        stream: false,
        reasoning_effort: 'none',
        thinking: { type: 'disabled' },
      }),
      signal: AbortSignal.timeout(55000),
    });
  } catch (error) {
    return res.status(502).json({ ok: false, error: `连接模型接口失败：${error.message}` });
  }

  const bodyText = await upstream.text();
  if (!upstream.ok) {
    let detail = bodyText.slice(0, 300);
    try {
      detail = JSON.parse(bodyText)?.error?.message || detail;
    } catch { /* 保持原文 */ }
    return res
      .status(upstream.status === 429 ? 429 : 502)
      .json({ ok: false, error: `模型接口返回 ${upstream.status}：${detail}` });
  }

  let content = '';
  try {
    content = JSON.parse(bodyText)?.choices?.[0]?.message?.content || '';
  } catch {
    return res.status(502).json({ ok: false, error: '模型返回的不是合法 JSON' });
  }

  const parsed = pickJson(content);
  const en = String(parsed?.en || '').trim();
  const zh = String(parsed?.zh || '').trim();
  if (!en) return res.status(422).json({ ok: false, error: '没有生成出可用的例句' });
  return res.status(200).json({ ok: true, en, zh });
}
