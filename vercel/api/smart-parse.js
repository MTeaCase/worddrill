/**
 * Vercel /api/smart-parse
 * 把任意文本整理成 | 英文 | 词性 | 汉语 | 表格。
 *
 * 环境变量：SENSENOVA_API_KEY（必填）、SENSENOVA_MODEL（可选）
 * 超时：见 vercel.json 的 maxDuration: 60
 */

const ENDPOINT = process.env.SENSENOVA_BASE_URL || 'https://token.sensenova.cn/v1/chat/completions';
const MAX_INPUT_CHARS = 6000;

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

function extractTable(raw) {
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
  return { markdown: [header, '| --- | --- | --- |', ...rows].join('\n'), count: rows.length };
}

export default async function handler(req, res) {
  if (req.method === 'OPTIONS') return res.status(200).json({ ok: true });
  if (req.method !== 'POST') return res.status(405).json({ ok: false, error: '只支持 POST' });

  const apiKey = process.env.SENSENOVA_API_KEY;
  if (!apiKey) return res.status(500).json({ ok: false, error: '没有配置 SENSENOVA_API_KEY' });

  const text = String(req.body?.text || '').trim();
  if (!text) return res.status(400).json({ ok: false, error: 'text 不能为空' });
  if (text.length > MAX_INPUT_CHARS) {
    return res.status(413).json({ ok: false, error: `内容太长（${text.length} 字），上限 ${MAX_INPUT_CHARS} 字` });
  }

  let upstream;
  try {
    upstream = await fetch(ENDPOINT, {
      method: 'POST',
      headers: { Authorization: `Bearer ${apiKey}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({
        model: process.env.SENSENOVA_MODEL || 'sensenova-6.8-flash-lite',
        messages: [
          { role: 'system', content: SYSTEM_PROMPT },
          { role: 'user', content: text },
        ],
        temperature: 0.1,
        max_tokens: 3000,
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

  const { markdown, count } = extractTable(content);
  if (count === 0) return res.status(422).json({ ok: false, error: 'AI 没有整理出词条' });
  return res.status(200).json({ ok: true, markdown, count });
}
