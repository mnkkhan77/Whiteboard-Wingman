import { useMemo, type ReactNode } from "react";
import { parseMarkdownLite, type Block, type Inline, type InlineLines } from "../utils/markdownLite";

type RenderCite = (n: number, key: string) => ReactNode;

function renderInline(nodes: Inline[], keyPrefix: string, renderCite: RenderCite): ReactNode[] {
  return nodes.map((node, i) => {
    const key = `${keyPrefix}.${i}`;
    switch (node.type) {
      case "text":
        return node.text; // React escapes it — model output is never parsed as HTML
      case "code":
        return <code key={key}>{node.text}</code>;
      case "strong":
        return <strong key={key}>{renderInline(node.children, key, renderCite)}</strong>;
      case "cite":
        return renderCite(node.n, key);
    }
  });
}

/** Soft line breaks inside a paragraph / list item become <br>. */
function renderLines(lines: InlineLines, keyPrefix: string, renderCite: RenderCite): ReactNode[] {
  return lines.flatMap((line, i) => {
    const content = renderInline(line, `${keyPrefix}.${i}`, renderCite);
    return i === 0 ? content : [<br key={`${keyPrefix}.br${i}`} />, ...content];
  });
}

function renderBlock(block: Block, key: string, renderCite: RenderCite): ReactNode {
  switch (block.type) {
    case "paragraph":
      return <p key={key}>{renderLines(block.lines, key, renderCite)}</p>;
    case "heading":
      return (
        <p key={key} className="chat-md-heading">
          {renderInline(block.content, key, renderCite)}
        </p>
      );
    case "list": {
      const children = block.items.map((item, i) => <li key={`${key}.${i}`}>{renderLines(item, `${key}.${i}`, renderCite)}</li>);
      return block.ordered ? (
        <ol key={key} start={block.start !== 1 ? block.start : undefined}>
          {children}
        </ol>
      ) : (
        <ul key={key}>{children}</ul>
      );
    }
    case "code":
      return (
        <pre key={key}>
          <code>{block.text}</code>
        </pre>
      );
  }
}

interface ChatMarkdownProps {
  text: string;
  /** Highest valid citation number — `[n]` above it (or any `[n]` with 0) stays plain text. */
  sourceCount: number;
  /** Renders a `[n]` marker (a CitationChip on the chat page). */
  renderCite: RenderCite;
}

/** Light markdown built as React elements — no dangerouslySetInnerHTML anywhere. */
export function ChatMarkdown({ text, sourceCount, renderCite }: ChatMarkdownProps) {
  const blocks = useMemo(() => parseMarkdownLite(text, sourceCount), [text, sourceCount]);
  return <div className="chat-markdown">{blocks.map((b, i) => renderBlock(b, `b${i}`, renderCite))}</div>;
}
