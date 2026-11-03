/**
 * Netlify Function（v1 handler）：给一个单词生成英中对照例句。
 * 生成结果由 App 存进本地数据库，同一个词不会重复请求。
 *
 * 请求：{"word":"abandon","meaning":"放弃"}
 * 返回：{"ok":true,"en":"...","zh":"..."}
 */

const DEFAULT_ENDPOINT = 'https://token.sensenova.cn/v1/chat/completions';

const SYSTEM_PROMPT = `你是英语例句生成器。给用户提供的单词写一句简单、地道、贴近高中水平的英文例句，并翻译成中文。

只输出一个 JSON 对象，不要解释，不要代码块：
{"en":"英文例句","zh":"中文翻译"}

要求：
1. 例句必须包含该单词（可用其变形）。
2. 长度 6–18 个单词。
3. 中文翻译自然、简洁。
4. 不要出现引号转义错误。`;

function reply(statusCode, body) {
  return {
    statusCode,
    headers: {
      'content-type': 'application/json; charset=utf-8',
      'access-control-allow-origin': process.env.ALLOWED_ORIGIN || '*',
      'access-control-allow-headers': 'content-type',
      'access-control-allow-methods': 'POST, OPTIONS',
      'cache-control': 'no-store',
    },
    body: JSON.stringify(body),
  };
}

/** 从模型输出里抠出第一个 JSON 对象。 */
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

exports.handler = async (event) => {
  if (event.httpMethod === 'OPTIONS') return reply(200, { ok: true });
  if (event.httpMethod !== 'POST') return reply(405, { ok: false, error: '只支持 POST' });

  const apiKey = process.env.SENSENOVA_API_KEY;
  if (!apiKey) return reply(500, { ok: false, error: '服务端没有配置 SENSENOVA_API_KEY' });

  let payload;
  try {
    payload = JSON.parse(event.body || '{}');
  } catch {
    return reply(400, { ok: false, error: '请求体不是合法 JSON' });
  }

  const word = String(payload?.word || '').trim();
  const meaning = String(payload?.meaning || '').trim();
  if (!word) return reply(400, { ok: false, error: 'word 不能为空' });
  if (word.length > 60) return reply(413, { ok: false, error: '单词太长' });

  let upstream;
  try {
    upstream = await fetch(process.env.SENSENOVA_BASE_URL || DEFAULT_ENDPOINT, {
      method: 'POST',
      headers: { Authorization: `Bearer ${apiKey}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({
        model: process.env.SENSENOVA_MODEL || 'sensenova-6.8-flash-lite',
        messages: [
          { role: 'system', content: SYSTEM_PROMPT },
          { role: 'user', content: meaning ? `${word}（${meaning}）` : word },
        ],
        temperature: 0.6,
        max_tokens: 300,
        stream: false,
        reasoning_effort: 'none',
        thinking: { type: 'disabled' },
      }),
      signal: AbortSignal.timeout(9000),
    });
  } catch (error) {
    return reply(502, { ok: false, error: `连接模型接口失败：${error.message}` });
  }

  const bodyText = await upstream.text();
  if (!upstream.ok) {
    let detail = bodyText.slice(0, 300);
    try {
      detail = JSON.parse(bodyText)?.error?.message || detail;
    } catch { /* 保持原文 */ }
    return reply(upstream.status === 429 ? 429 : 502, {
      ok: false,
      error: upstream.status === 429 ? `模型接口限流或额度不足：${detail}` : `模型接口返回 ${upstream.status}：${detail}`,
    });
  }

  let content = '';
  try {
    content = JSON.parse(bodyText)?.choices?.[0]?.message?.content || '';
  } catch {
    return reply(502, { ok: false, error: '模型返回的不是合法 JSON' });
  }

  const parsed = pickJson(content);
  const en = String(parsed?.en || '').trim();
  const zh = String(parsed?.zh || '').trim();
  if (!en) return reply(422, { ok: false, error: '没有生成出可用的例句' });
  return reply(200, { ok: true, en, zh });
};
