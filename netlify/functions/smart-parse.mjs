/**
 * Netlify Function：把任意文本整理成 WordDrill 能导入的 Markdown 表格。
 *
 * 环境变量（在 Netlify 站点设置 → Environment variables 里配置）：
 *   SENSENOVA_API_KEY   必填，商汤日日新控制台创建的 API Key
 *   SENSENOVA_MODEL     可选，默认 SenseChat-5
 *   ALLOWED_ORIGIN      可选，CORS 白名单，多个用逗号分隔，默认 *
 *
 * 调用：POST /api/smart-parse  {"text": "..."}
 * 返回：{"ok": true, "markdown": "| 英文 | 词性 | 汉语 |...", "count": 12}
 */

const SENSENOVA_URL = 'https://api.sensenova.cn/compatible-mode/v2/chat/completions';
const MAX_INPUT_CHARS = 12000;

const SYSTEM_PROMPT = `你是一个词汇表格式化助手。用户会给你一段任意格式的内容（可能是单词列表、CSV、带音标或例句的词汇表、课文生词、OCR 识别结果等），你要把它整理成 Markdown 表格。

输出格式（严格遵守）：
| 英文 | 词性 | 汉语 |
| --- | --- | --- |
| abandon | v. | 抛弃；放弃 |

规则：
1. 只输出表格本身，不要任何解释、标题、前后缀或代码块标记。
2. 每行一个词条，三列固定为：英文、词性、中文释义。
3. 词性用简写：n. v. vt. vi. adj. adv. prep. conj. pron. num. art. int. phr.；无法判断时留空。
4. 中文释义简洁，多个义项用「；」分隔，不要写音标、例句、编号、页码。
5. 只保留英文单词或短语，剔除纯中文行、标题行、说明行等无关内容。
6. 同一个词只保留一次（不区分大小写）。
7. 不要编造用户没有提供的单词。`;

function json(body, status = 200) {
  const origin = process.env.ALLOWED_ORIGIN || '*';
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      'content-type': 'application/json; charset=utf-8',
      'access-control-allow-origin': origin,
      'access-control-allow-headers': 'content-type',
      'access-control-allow-methods': 'POST, OPTIONS',
      'cache-control': 'no-store',
    },
  });
}

/** 从模型输出里抠出表格行，顺便去掉代码块围栏和多余说明。 */
function extractTable(raw) {
  const lines = String(raw || '')
    .replace(/\r/g, '')
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line.startsWith('|') && line.indexOf('|', 1) > 0);

  const isSeparator = (line) => /^\|[\s:|-]+\|$/.test(line);
  const isHeader = (line) => line.includes('英文') && (line.includes('汉语') || line.includes('中文'));

  const rows = lines.filter((line) => !isSeparator(line) && !isHeader(line));
  const hasHeader = lines.some(isHeader);
  const header = hasHeader ? lines.find(isHeader) : '| 英文 | 词性 | 汉语 |';
  return {
    markdown: [header, '| --- | --- | --- |', ...rows].join('\n'),
    count: rows.length,
  };
}

export default async (req) => {
  if (req.method === 'OPTIONS') return json({ ok: true });
  if (req.method !== 'POST') return json({ ok: false, error: '只支持 POST' }, 405);

  const apiKey = process.env.SENSENOVA_API_KEY;
  if (!apiKey) return json({ ok: false, error: '服务端没有配置 SENSENOVA_API_KEY' }, 500);

  let payload;
  try {
    payload = await req.json();
  } catch {
    return json({ ok: false, error: '请求体不是合法 JSON' }, 400);
  }

  const text = String(payload?.text || '').trim();
  if (!text) return json({ ok: false, error: 'text 不能为空' }, 400);
  if (text.length > MAX_INPUT_CHARS) {
    return json({ ok: false, error: `内容太长（${text.length} 字），上限 ${MAX_INPUT_CHARS} 字` }, 413);
  }

  let upstream;
  try {
    upstream = await fetch(SENSENOVA_URL, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${apiKey}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        model: process.env.SENSENOVA_MODEL || 'SenseChat-5',
        messages: [
          { role: 'system', content: SYSTEM_PROMPT },
          { role: 'user', content: text },
        ],
        temperature: 0.1,
        top_p: 0.7,
        max_completion_tokens: 4096,
        stream: false,
      }),
      signal: AbortSignal.timeout(55000),
    });
  } catch (error) {
    return json({ ok: false, error: `连接商汤接口失败：${error.message}` }, 502);
  }

  const bodyText = await upstream.text();
  if (!upstream.ok) {
    return json({ ok: false, error: `商汤接口返回 ${upstream.status}：${bodyText.slice(0, 300)}` }, 502);
  }

  let content = '';
  try {
    content = JSON.parse(bodyText)?.choices?.[0]?.message?.content || '';
  } catch {
    return json({ ok: false, error: '商汤返回的不是合法 JSON' }, 502);
  }

  const { markdown, count } = extractTable(content);
  if (count === 0) {
    return json({ ok: false, error: 'AI 没有整理出词条，换个格式或分段再试' }, 422);
  }
  return json({ ok: true, markdown, count });
};

export const config = { path: '/api/smart-parse' };
