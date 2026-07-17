/**
 * 字幕解析模块
 * 支持：SRT、WebVTT、ASS/SSA（仅文本提取）
 */

export interface SubtitleCue {
  id: string;
  start: number; // 秒
  end: number;   // 秒
  text: string;  // 纯文本（已去除样式标签）
}

// ─── SRT 解析 ────────────────────────────────────────────────────────────────
function parseSrt(content: string): SubtitleCue[] {
  const cues: SubtitleCue[] = [];
  // 分割成块（\n\n 或 \r\n\r\n）
  const blocks = content.trim().split(/\r?\n\r?\n/);
  for (const block of blocks) {
    const lines = block.split(/\r?\n/).map(l => l.trim()).filter(Boolean);
    if (lines.length < 3) continue;
    const idLine = lines[0];
    const timeLine = lines[1];
    const textLines = lines.slice(2);

    const timeMatch = timeLine.match(
      /(\d{2}:\d{2}:\d{2}[,.:]\d{3})\s*-->\s*(\d{2}:\d{2}:\d{2}[,.:]\d{3})/
    );
    if (!timeMatch) continue;

    cues.push({
      id: idLine,
      start: parseSrtTime(timeMatch[1]),
      end: parseSrtTime(timeMatch[2]),
      text: stripHtml(textLines.join('\n')),
    });
  }
  return cues;
}

function parseSrtTime(t: string): number {
  // HH:MM:SS,mmm 或 HH:MM:SS.mmm
  const parts = t.replace(',', '.').split(':');
  const h = parseInt(parts[0], 10);
  const m = parseInt(parts[1], 10);
  const s = parseFloat(parts[2]);
  return h * 3600 + m * 60 + s;
}

// ─── WebVTT 解析 ─────────────────────────────────────────────────────────────
function parseVtt(content: string): SubtitleCue[] {
  // VTT 与 SRT 格式相近，去掉头部 WEBVTT 后可复用
  const stripped = content.replace(/^WEBVTT.*\n(\n)?/m, '').trim();
  return parseSrt(stripped);
}

// ─── ASS/SSA 解析（文本提取）─────────────────────────────────────────────────
function parseAss(content: string): SubtitleCue[] {
  const cues: SubtitleCue[] = [];
  const lines = content.split(/\r?\n/);
  let inEvents = false;
  let formatCols: string[] = [];

  for (const line of lines) {
    if (line.startsWith('[Events]')) { inEvents = true; continue; }
    if (line.startsWith('[') && inEvents) break;
    if (!inEvents) continue;

    if (line.startsWith('Format:')) {
      formatCols = line.replace('Format:', '').split(',').map(s => s.trim().toLowerCase());
      continue;
    }
    if (!line.startsWith('Dialogue:')) continue;

    const vals = line.replace('Dialogue:', '').split(',');
    const get = (name: string) => {
      const idx = formatCols.indexOf(name);
      return idx >= 0 ? (vals[idx] ?? '').trim() : '';
    };

    const startRaw = get('start');
    const endRaw = get('end');
    // 剩余部分全部是 Text（可能含逗号）
    const textIdx = formatCols.indexOf('text');
    const textRaw = textIdx >= 0 ? vals.slice(textIdx).join(',').trim() : '';

    const text = stripAssOverrides(textRaw);
    if (!text) continue;

    cues.push({
      id: `ass-${cues.length}`,
      start: parseAssTime(startRaw),
      end: parseAssTime(endRaw),
      text,
    });
  }
  return cues;
}

function parseAssTime(t: string): number {
  // H:MM:SS.cc
  const parts = t.split(':');
  if (parts.length < 3) return 0;
  const h = parseInt(parts[0], 10);
  const m = parseInt(parts[1], 10);
  const s = parseFloat(parts[2]);
  return h * 3600 + m * 60 + s;
}

function stripAssOverrides(text: string): string {
  // 移除 {...} 覆盖标签和 \N 换行
  return text
    .replace(/\{[^}]*\}/g, '')
    .replace(/\\N/gi, '\n')
    .replace(/\\n/gi, '\n')
    .trim();
}

// ─── 通用：去除 HTML 标签 ─────────────────────────────────────────────────────
function stripHtml(text: string): string {
  return text.replace(/<[^>]+>/g, '').trim();
}

// ─── 主入口：自动检测格式 ─────────────────────────────────────────────────────
export function parseSubtitle(content: string): SubtitleCue[] {
  const trimmed = content.trim();
  if (trimmed.startsWith('WEBVTT')) return parseVtt(trimmed);
  if (/^\[Script Info\]/m.test(trimmed) || /^\[V4/m.test(trimmed)) return parseAss(trimmed);
  return parseSrt(trimmed);
}

// ─── 查找当前时间对应字幕 ─────────────────────────────────────────────────────
export function findActiveCue(cues: SubtitleCue[], timeSeconds: number): SubtitleCue | null {
  for (const cue of cues) {
    if (timeSeconds >= cue.start && timeSeconds <= cue.end) return cue;
  }
  return null;
}
