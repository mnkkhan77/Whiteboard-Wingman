// "Markdown-lite" for chat answers: paragraphs, `-` / `*` / `1.` lists, inline `code`, **bold**,
// fenced code blocks, `#` headings, and inline `[n]` citation markers. Parses into a small AST
// that components/ChatMarkdown.tsx maps onto React elements — nothing is ever injected as HTML,
// so model output can't smuggle markup in. Framework-free so it can be exercised with plain Node.

export type Inline =
  | { type: "text"; text: string }
  | { type: "code"; text: string }
  | { type: "strong"; children: Inline[] }
  | { type: "cite"; n: number };

/** A list item or paragraph is a list of lines (soft line breaks are kept — chat text uses them). */
export type InlineLines = Inline[][];

export type Block =
  | { type: "paragraph"; lines: InlineLines }
  | { type: "heading"; content: Inline[] }
  | { type: "list"; ordered: boolean; start: number; items: InlineLines[] }
  | { type: "code"; text: string };

export type CitationToken = { type: "text"; text: string } | { type: "cite"; n: number };

const CITATION_RE = /\[(\d{1,3})\]/g;

/**
 * Splits text into plain runs and `[n]` citation markers. Only 1 ≤ n ≤ sourceCount becomes a
 * citation — anything else (e.g. "[0]", "[42]" with 3 sources, or no sources at all) stays text.
 */
export function tokenizeCitations(text: string, sourceCount: number): CitationToken[] {
  const out: CitationToken[] = [];
  const pushText = (t: string) => {
    if (!t) return;
    const last = out[out.length - 1];
    if (last && last.type === "text") last.text += t;
    else out.push({ type: "text", text: t });
  };
  let pos = 0;
  for (const m of text.matchAll(CITATION_RE)) {
    const n = Number(m[1]);
    const at = m.index ?? 0;
    if (n >= 1 && n <= sourceCount) {
      pushText(text.slice(pos, at));
      out.push({ type: "cite", n });
    } else {
      pushText(text.slice(pos, at + m[0].length));
    }
    pos = at + m[0].length;
  }
  pushText(text.slice(pos));
  return out;
}

function pushInline(out: Inline[], node: Inline) {
  const last = out[out.length - 1];
  if (node.type === "text") {
    if (!node.text) return;
    if (last && last.type === "text") {
      last.text += node.text;
      return;
    }
  }
  out.push(node);
}

const STRONG_RE = /\*\*(?=\S)([\s\S]*?\S)\*\*/g;

/** Bold + citations for a run that contains no code spans. */
function tokenizeProse(text: string, sourceCount: number, out: Inline[]) {
  let pos = 0;
  for (const m of text.matchAll(STRONG_RE)) {
    const at = m.index ?? 0;
    for (const t of tokenizeCitations(text.slice(pos, at), sourceCount)) pushInline(out, t);
    const children: Inline[] = [];
    for (const t of tokenizeCitations(m[1], sourceCount)) pushInline(children, t);
    out.push({ type: "strong", children });
    pos = at + m[0].length;
  }
  for (const t of tokenizeCitations(text.slice(pos), sourceCount)) pushInline(out, t);
}

/** One line of text → inline nodes. Code spans win (no bold/citations inside them); an unmatched
 *  backtick is literal text. */
export function tokenizeInline(text: string, sourceCount: number): Inline[] {
  const out: Inline[] = [];
  let pos = 0;
  while (pos < text.length) {
    const open = text.indexOf("`", pos);
    if (open === -1) break;
    const close = text.indexOf("`", open + 1);
    if (close === -1) break;
    if (close === open + 1) {
      // "``" — empty span, keep literally
      tokenizeProse(text.slice(pos, close + 1), sourceCount, out);
      pos = close + 1;
      continue;
    }
    tokenizeProse(text.slice(pos, open), sourceCount, out);
    out.push({ type: "code", text: text.slice(open + 1, close) });
    pos = close + 1;
  }
  tokenizeProse(text.slice(pos), sourceCount, out);
  return out;
}

const FENCE_RE = /^\s*```/;
const BULLET_RE = /^\s*[-*•]\s+(.*)$/;
const ORDERED_RE = /^\s*(\d{1,3})[.)]\s+(.*)$/;
const HEADING_RE = /^\s{0,3}#{1,6}\s+(.*?)\s*#*\s*$/;
const INDENTED_RE = /^\s+\S/;

/**
 * Block-level parse. Tolerates half-streamed input: an unclosed fence runs to the end, a
 * half-typed "[1" or "**bold" is just text until the rest arrives.
 */
export function parseMarkdownLite(text: string, sourceCount = 0): Block[] {
  const lines = text.split(/\r\n|\r|\n/);
  const blocks: Block[] = [];
  // The open paragraph/list that following lines may extend (null after a blank line etc.).
  let current: Block | null = null;

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];

    if (FENCE_RE.test(line)) {
      current = null;
      const body: string[] = [];
      i++;
      while (i < lines.length && !FENCE_RE.test(lines[i])) body.push(lines[i++]);
      blocks.push({ type: "code", text: body.join("\n") });
      continue;
    }

    if (line.trim() === "") {
      current = null;
      continue;
    }

    const heading = HEADING_RE.exec(line);
    if (heading) {
      current = null;
      blocks.push({ type: "heading", content: tokenizeInline(heading[1], sourceCount) });
      continue;
    }

    const bullet = BULLET_RE.exec(line);
    const ordered = bullet ? null : ORDERED_RE.exec(line);
    if (bullet || ordered) {
      const isOrdered = !!ordered;
      const itemText = bullet ? bullet[1] : ordered![2];
      if (current && current.type === "list" && current.ordered === isOrdered) {
        current.items.push([tokenizeInline(itemText, sourceCount)]);
      } else {
        const list: Block = {
          type: "list",
          ordered: isOrdered,
          start: ordered ? Number(ordered[1]) : 1,
          items: [[tokenizeInline(itemText, sourceCount)]],
        };
        blocks.push(list);
        current = list;
      }
      continue;
    }

    if (current && current.type === "list" && INDENTED_RE.test(line)) {
      // indented continuation of the previous list item
      current.items[current.items.length - 1].push(tokenizeInline(line.trim(), sourceCount));
    } else if (current && current.type === "paragraph") {
      current.lines.push(tokenizeInline(line, sourceCount));
    } else {
      const para: Block = { type: "paragraph", lines: [tokenizeInline(line, sourceCount)] };
      blocks.push(para);
      current = para;
    }
  }
  return blocks;
}

function inlineToPlain(nodes: Inline[]): string {
  return nodes
    .map((n) => {
      switch (n.type) {
        case "text":
        case "code":
          return n.text;
        case "strong":
          return inlineToPlain(n.children);
        case "cite":
          return `[${n.n}]`;
      }
    })
    .join("");
}

/** Readable plain text (markdown syntax removed) — used for the screen-reader announcement. */
export function markdownLiteToPlainText(blocks: Block[]): string {
  return blocks
    .map((b) => {
      switch (b.type) {
        case "paragraph":
          return b.lines.map(inlineToPlain).join("\n");
        case "heading":
          return inlineToPlain(b.content);
        case "list":
          return b.items.map((item) => item.map(inlineToPlain).join(" ")).join("\n");
        case "code":
          return b.text;
      }
    })
    .join("\n\n");
}
