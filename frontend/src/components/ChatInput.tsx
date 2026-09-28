import { useEffect, useId, useRef, useState, type FormEvent, type KeyboardEvent } from "react";
import { CHAT_MESSAGE_MAX_CHARS } from "../utils/chat";

interface ChatInputProps {
  /** Called with the raw text; the box is cleared right away. */
  onSend: (text: string) => void;
  onStop: () => void;
  streaming: boolean;
  /** Can't send at all right now (quota used up, still loading, …). */
  disabled?: boolean;
  placeholder?: string;
}

const COUNTER_WARN_AT = CHAT_MESSAGE_MAX_CHARS - 200;

/** Textarea composer: Enter sends, Shift+Enter adds a newline, 2000-char cap with a counter.
 *  Locked while an answer streams; the Send button turns into Stop. */
export function ChatInput({ onSend, onStop, streaming, disabled = false, placeholder }: ChatInputProps) {
  const [text, setText] = useState("");
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const wasStreaming = useRef(false);
  const hintId = useId();
  const inputId = useId();
  const locked = disabled || streaming;
  const canSend = !locked && text.trim().length > 0;

  // The textarea is disabled while streaming, which drops focus to <body>; hand it back once the
  // answer ends so the user can type the follow-up — unless they've since focused something else.
  useEffect(() => {
    if (wasStreaming.current && !streaming && !disabled) {
      const focused = document.activeElement;
      if (!focused || focused === document.body) textareaRef.current?.focus();
    }
    wasStreaming.current = streaming;
  }, [streaming, disabled]);

  const submit = () => {
    if (!canSend) return;
    onSend(text);
    setText("");
  };

  const handleSubmit = (e: FormEvent) => {
    e.preventDefault();
    submit();
  };

  const handleKeyDown = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    // isComposing: Enter that confirms an IME candidate (CJK input) must not send.
    if (e.key === "Enter" && !e.shiftKey && !e.nativeEvent.isComposing) {
      e.preventDefault();
      submit();
    }
  };

  return (
    <form className="chat-input" onSubmit={handleSubmit}>
      <label htmlFor={inputId} className="visually-hidden">
        Ask a question about this pack
      </label>
      <textarea
        id={inputId}
        ref={textareaRef}
        rows={2}
        value={text}
        maxLength={CHAT_MESSAGE_MAX_CHARS}
        placeholder={placeholder ?? "Ask a question about this document…"}
        disabled={locked}
        aria-describedby={hintId}
        onChange={(e) => setText(e.target.value)}
        onKeyDown={handleKeyDown}
      />
      <div className="chat-input-footer">
        <span id={hintId} className="chat-input-hint">
          <span className="chat-input-keys">Enter to send · Shift+Enter for a new line · </span>
          <span className={text.length >= COUNTER_WARN_AT ? "chat-input-count chat-input-count-warn" : "chat-input-count"}>
            {text.length} / {CHAT_MESSAGE_MAX_CHARS}
            <span className="visually-hidden"> characters</span>
          </span>
        </span>
        {streaming ? (
          <button type="button" className="secondary pack-small-button" onClick={onStop}>
            <span aria-hidden>■</span> Stop
          </button>
        ) : (
          <button type="submit" className="primary pack-small-button" disabled={!canSend}>
            Send
          </button>
        )}
      </div>
    </form>
  );
}
