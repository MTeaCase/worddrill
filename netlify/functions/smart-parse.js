/**
 * Netlify Function（v1 handler）：把任意文本整理成 WordDrill 能导入的 Markdown 表格。
 *
 * 环境变量：
 *   SENSENOVA_API_KEY   必填
 *   SENSENOVA_MODEL     可选，默认 sensenova-6.8-flash-lite
 *   SENSENOVA_BASE_URL  可选，默认 https://token.sensenova.cn/v1/chat/completions
 *   ALLOWED_ORIGIN      可选，默认 *
 *
 * 对外路径：/api/smart-parse（netlify.toml 里重写到 /.netlify/functions/smart-parse）
 * 请求：{"text": "..."}
 * 返回：{"ok": true, "markdown": "...", "count": 12}
 */

const DEFAULT_ENDPOINT = 'https://token.sensenova.cn/v1/chat/completions';
const MAX_INPUT_CHARS = 3000;

const SYSTEM_PROMPT = `你是词汇表格式化工具。把用户给的任意内容整理成 Markdown 表格。

输出格式（严格遵守）：
| 英文 | 词性 | 汉语 |
| --- | --- | --- |
| abandon | v. | 抛弃；放弃 |

规则：
1. 只输出表格本身，不要解释、标题、前后缀或代码块标记。
2. 每行一个词条，三列：英文、词性、中文释义。
3. 词性用简写：n. v. vt. vi. adj. adv. prep. conj. pron. num. art. int. phr.；无法判断时留空。
4. 中文释义简洁，多个义项用「；」分隔；不要音标、例句、编号、页码。
5. 只保留英文单词或短语，剔除纯中文行、标题行、说明行。
6. 同一个词只保留一次（不区分大小写）。
7. 不要编造用户没有提供的单词。`;

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

/** 从模型输出里抠出表格行，去掉代码块围栏和多余说明。 */
function extractTable(raw) {
  const lines = String(raw || '')
    .replace(/\r/g, '')
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line.startsWith('|') && line.indexOf('|', 1) > 0);

  const isSeparator = (line) => /^\|[\s:|-]+\|$/.test(line);
  const isHeader = (line) => line.includes('英文') && (line.includes('汉语') || line.includes('中文'));

  const rows = lines.filter((line) => !isSeparator(line) && !isHeader(line));
  const header = lines.find(isHeader) || '| 英文 | 词性 | 汉语 |';
  return {
    markdown: [header, '| --- | --- | --- |', ...rows].join('\n'),
    count: rows.length,
  };
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

  const text = String(payload?.text || '').trim();
  if (!text) return reply(400, { ok: false, error: 'text 不能为空' });
  if (text.length > MAX_INPUT_CHARS) {
    return reply(413, { ok: false, error: `这一段太长（${text.length} 字），上限 ${MAX_INPUT_CHARS} 字，请分段` });
  }

  let upstream;
  try {
    upstream = await fetch(process.env.SENSENOVA_BASE_URL || DEFAULT_ENDPOINT, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${apiKey}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        model: process.env.SENSENOVA_MODEL || 'sensenova-6.8-flash-lite',
        messages: [
          { role: 'system', content: SYSTEM_PROMPT },
          { role: 'user', content: text },
        ],
        temperature: 0.1,
        max_tokens: 1500,
        stream: false,
        // 该模型默认先输出 reasoning，几十个词就要十几秒，会撞上函数 10 秒超时
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
    if (upstream.status === 429) {
      return reply(429, { ok: false, error: `模型接口限流或额度不足：${detail}` });
    }
    return reply(502, { ok: false, error: `模型接口返回 ${upstream.status}：${detail}` });
  }

  let content = '';
  let finish = '';
  try {
    const parsed = JSON.parse(bodyText);
    content = parsed?.choices?.[0]?.message?.content || '';
    finish = parsed?.choices?.[0]?.finish_reason || '';
  } catch {
    return reply(502, { ok: false, error: '模型返回的不是合法 JSON' });
  }

  if (!content.trim()) {
    return reply(502, { ok: false, error: '模型没有返回内容，可能被截断或触发限流，稍后再试' });
  }

  const { markdown, count } = extractTable(content);
  if (count === 0) {
    return reply(422, { ok: false, error: 'AI 没有整理出词条，换个格式或分段再试' });
  }
  return reply(200, { ok: true, markdown, count, truncated: finish === 'length' });
};
