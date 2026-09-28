import { useId, useRef, useState } from "react";
import { ChatMarkdown } from "./ChatMarkdown";
import { CitationChip } from "./CitationChip";
import { formatSourcePages } from "../utils/chat";
import type { ChatItem } from "../hooks/usePackChat";
import type { ChatSourceDto } from "../types/api";

function TypingIndicator() {
  return (
    <span className="chat-typing" aria-hidden>
      <span />
      <span />
      <span />
    </span>
  );
}

function SourceDetails({ source, onClose }: { source: ChatSourceDto; onClose: () => void }) {
  const pages = formatSourcePages(source);
  return (
    <>
      <div className="chat-source-header">
        <span className="chat-source-number" aria-hidden>
          {source.n}
        </span>
        <div className="chat-source-meta">
          <strong>
            Source {source.n}
            {pages && ` · ${pages}`}
          </strong>
          {source.section && <span className="pack-wrap">{source.section}</span>}
        </div>
        <button type="button" className="chat-source-close" aria-label="Close source" onClick={onClose}>
          ×
        </button>
      </div>
      <blockquote className="chat-source-snippet">{source.snippet}</blockquote>
    </>
  );
}

function AssistantMessage({ item }: { item: ChatItem }) {
  const [activeN, setActiveN] = useState<number | null>(null);
  const panelId = useId();
  // The chip that opened the panel, so closing it from the panel returns focus there.
  const openerRef = useRef<HTMLButtonElement | null>(null);

  const bySourceN = new Map(item.sources.map((s) => [s.n, s]));
  const maxN = item.sources.reduce((max, s) => Math.max(max, s.n), 0);
  const active = activeN !== null ? bySourceN.get(activeN) : undefined;

  const activate = (n: number, chip: HTMLButtonElement) => {
    openerRef.current = chip;
    setActiveN(n);
  };
  const close = () => setActiveN(null);
  const closeFromPanel = () => {
    setActiveN(null);
    openerRef.current?.focus({ preventScroll: true });
  };

  const waiting = item.streaming && !item.content;

  return (
    <div className="chat-bubble chat-bubble-assistant">
      <span className="visually-hidden">Assistant:</span>
      {waiting ? (
        <TypingIndicator />
      ) : (
        <ChatMarkdown
          text={item.content}
          sourceCount={maxN}
          renderCite={(n, key) => {
            const source = bySourceN.get(n);
            if (!source) return `[${n}]`;
            return (
              <CitationChip
                key={key}
                source={source}
                active={activeN === n}
                panelId={panelId}
                onActivate={activate}
                onClose={close}
              />
            );
          }}
        />
      )}
      {item.streaming && item.content && <span className="chat-cursor" aria-hidden />}
      {item.stopped && <p className="chat-note">Stopped — this partial answer wasn't saved.</p>}
      {/* Always rendered (hidden while empty) so it's already a live region when a source opens —
          no role="region" here, which would add a landmark per message. */}
      <div
        id={panelId}
        className={active ? "chat-source-panel" : "chat-source-panel chat-source-panel-empty"}
        aria-live="polite"
        onKeyDown={(e) => {
          if (e.key === "Escape" && active) {
            e.preventDefault();
            closeFromPanel();
          }
        }}
      >
        {active && <SourceDetails source={active} onClose={closeFromPanel} />}
      </div>
    </div>
  );
}

export function ChatMessage({ item }: { item: ChatItem }) {
  if (item.role === "USER") {
    return (
      <div className="chat-bubble chat-bubble-user">
        <span className="visually-hidden">You: </span>
        {item.content}
      </div>
    );
  }
  return <AssistantMessage item={item} />;
}
