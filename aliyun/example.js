/**
 * 阿里云函数计算 FC —— HTTP 函数：生成例句
 *
 * 触发器：HTTP 触发器，请求方式 POST，路径 /example
 * 运行环境：Node.js 18，超时 60 秒
 * 环境变量：SENSENOVA_API_KEY
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

function reply(statusCode, body) {
  return {
    statusCode,
    headers: { 'content-type': 'application/json; charset=utf-8' },
    body: JSON.stringify(body),
  };
}

function readRequest(event) {
  const method = String(event.httpMethod || event.method || 'POST').toUpperCase();
  let body = event.body || '';
  if (event.isBase64Encoded) body = Buffer.from(body, 'base64').toString('utf8');
  let json = {};
  try {
    json = typeof body === 'string' ? JSON.parse(body || '{}') : body || {};
  } catch { /* 上层报 400 */ }
  return { method, json, body };
}

exports.handler = async (event) => {
  const { method, json, body } = readRequest(event);
  if (method === 'OPTIONS') return reply(200, { ok: true });
  if (method !== 'POST') return reply(405, { ok: false, error: '只支持 POST' });

  const apiKey = process.env.SENSENOVA_API_KEY;
  if (!apiKey) return reply(500, { ok: false, error: '没有配置 SENSENOVA_API_KEY' });
  if (!body || !String(body).trim()) return reply(400, { ok: false, error: '请求体为空' });

  const word = String(json?.word || '').trim();
  const meaning = String(json?.meaning || '').trim();
  if (!word) return reply(400, { ok: false, error: 'word 不能为空' });
  if (word.length > 60) return reply(413, { ok: false, error: '单词太长' });

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
      error: `模型接口返回 ${upstream.status}：${detail}`,
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
