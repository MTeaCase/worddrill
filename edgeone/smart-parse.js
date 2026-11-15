/**
 * EdgeOne 边缘函数：把任意文本整理成 WordDrill 能导入的三字段文本行。
 *
 * 与 netlify/functions/smart-parse.js 逻辑一致，只是入口换成 EdgeOne 的 Fetch 事件，
 * 环境变量换成 EdgeOne 的 env 对象（变量名不能含下划线，所以用点分名）。
 *
 * 环境变量（控制台 → 边缘函数 → 函数管理 → 环境变量与密钥）：
 *   SENSENOVA.API.KEY    Secret，必填，值形如 sk-...
 *   SENSENOVA.MODEL      String，可选，默认 sensenova-6.8-flash-lite
 *   SENSENOVA.BASE.URL   String，可选，默认 https://token.sensenova.cn/v1/chat/completions
 *   ALLOWED.ORIGIN       String，可选，默认 *
 *
 * 触发规则：URL Path 等于 /api/smart-parse
 * 请求：{"text": "..."}
 * 返回：{"ok": true, "markdown": "...", "count": 12}
 */

const DEFAULT_ENDPOINT = 'https://token.sensenova.cn/v1/chat/completions';
const MAX_INPUT_CHARS = 3000;
const UPSTREAM_TIMEOUT_MS = 9000;

const SYSTEM_PROMPT = `把用户给的任意内容整理成纯文本清单，只输出文本行，不要使用排版格式。

输出格式（严格遵守）：每行一个词条，用竖线 | 分隔三个字段，顺序固定为「英文 | 词性 | 汉语」。
竖线是格式的一部分，每行都必须保留三个分隔符。

第一行固定写：英文 | 词性 | 汉语
第二行固定写：--- | --- | ---
之后每行一个词条，例如：abandon | v. | 抛弃；放弃

规则：
1. 只输出这些文本行，不要解释、标题、前后缀或代码块标记；不要输出真正的表格。
2. 词性用简写：n. v. vt. vi. adj. adv. prep. conj. pron. num. art. int. phr.；无法判断时留空。
3. 中文释义简洁，多个义项用「；」分隔；不要音标、例句、编号、页码。
4. 只保留英文单词或短语，剔除纯中文行、标题行、说明行。
5. 同一个词只保留一次（不区分大小写）。
6. 不要编造用户没有提供的单词。`;

function reply(status, body) {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      'content-type': 'application/json; charset=utf-8',
      'access-control-allow-origin': env['ALLOWED.ORIGIN'] || '*',
      'access-control-allow-headers': 'content-type',
      'access-control-allow-methods': 'POST, OPTIONS',
      'cache-control': 'no-store',
    },
  });
}

/** 从模型输出里抠出文本行，去掉代码块围栏和多余说明。 */
function extractRows(raw) {
  const lines = String(raw || '')
    .replace(/\r/g, '')
    .split('\n')
    .map((line) => line.trim())
    // 允许模型输出不带首尾竖线的「a | b | c」，这里统一补成 | a | b | c |
    .filter((line) => (line.match(/\|/g) || []).length >= 2)
    .map((line) => `| ${line.replace(/^\||\|$/g, '').split('|').map((cell) => cell.trim()).join(' | ')} |`);

  const isSeparator = (line) => /^\|[\s:|-]+\|$/.test(line);
  const isHeader = (line) => line.includes('英文') && (line.includes('汉语') || line.includes('中文'));

  const rows = lines.filter((line) => !isSeparator(line) && !isHeader(line));
  const header = lines.find(isHeader) || '| 英文 | 词性 | 汉语 |';
  return {
    markdown: [header, '| --- | --- | --- |', ...rows].join('\n'),
    count: rows.length,
  };
}

addEventListener('fetch', (event) => {
  event.respondWith(handleRequest(event.request));
});

async function handleRequest(request) {
  if (request.method === 'OPTIONS') return reply(200, { ok: true });
  if (request.method !== 'POST') return reply(405, { ok: false, error: '只支持 POST' });

  const apiKey = env['SENSENOVA.API.KEY'];
  if (!apiKey) return reply(500, { ok: false, error: '服务端没有配置 SENSENOVA.API.KEY' });

  let payload;
  try {
    payload = await request.json();
  } catch {
    return reply(400, { ok: false, error: '请求体不是合法 JSON' });
  }

  const text = String(payload?.text || '').trim();
  if (!text) return reply(400, { ok: false, error: 'text 不能为空' });
  if (text.length > MAX_INPUT_CHARS) {
    return reply(413, {
      ok: false,
      error: `这一段太长（${text.length} 字），上限 ${MAX_INPUT_CHARS} 字，请分段`,
    });
  }

  // EdgeOne 的运行时是 Fetch 标准实现，不保证有 AbortSignal.timeout，手动兜一层
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), UPSTREAM_TIMEOUT_MS);
  let upstream;
  try {
    upstream = await fetch(env['SENSENOVA.BASE.URL'] || DEFAULT_ENDPOINT, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${apiKey}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        model: env['SENSENOVA.MODEL'] || 'sensenova-6.8-flash-lite',
        messages: [
          { role: 'system', content: SYSTEM_PROMPT },
          { role: 'user', content: text },
        ],
        temperature: 0.1,
        max_tokens: 1500,
        stream: false,
        // 该模型默认先输出 reasoning，几十个词就要十几秒，关掉思考才能压到几秒
        reasoning_effort: 'none',
        thinking: { type: 'disabled' },
      }),
      signal: controller.signal,
    });
  } catch (error) {
    const message = error?.name === 'AbortError'
      ? `模型接口超时（${UPSTREAM_TIMEOUT_MS / 1000} 秒）`
      : `连接模型接口失败：${error.message}`;
    return reply(502, { ok: false, error: message });
  } finally {
    clearTimeout(timer);
  }

  const bodyText = await upstream.text();
  if (!upstream.ok) {
    let detail = bodyText.slice(0, 300);
    try {
      detail = JSON.parse(bodyText)?.error?.message || detail;
    } catch {
      /* 保持原文 */
    }
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
    return reply(502, { ok: false, error: '模型没有返回内容，可能被截断或触发限流' });
  }

  const { markdown, count } = extractRows(content);
  if (count === 0) {
    return reply(422, { ok: false, error: '没有整理出词条，换个格式或分段再试' });
  }
  return reply(200, { ok: true, markdown, count, truncated: finish === 'length' });
}