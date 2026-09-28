import { useCallback, useEffect, useLayoutEffect, useRef, useState, type ReactNode } from "react";
import { Link, useParams } from "react-router-dom";
import { useAuth } from "../context/AuthContext";
import { Navbar } from "../components/Navbar";
import { ChatMessage } from "../components/ChatMessage";
import { ChatInput } from "../components/ChatInput";
import { QuotaMeter } from "../components/QuotaMeter";
import { usePackChat } from "../hooks/usePackChat";
import { ApiError } from "../api/client";
import { CHAT_EXAMPLE_PROMPTS, chatErrorMessage, isQuotaExhausted, quotaResetMessage } from "../utils/chat";
import { isPackInFlight, packErrorMessage } from "../utils/packs";

/** How close (px) to the bottom still counts as "following" the conversation. */
const NEAR_BOTTOM_PX = 120;

/**
 * Keeps the window scrolled to the bottom as `content` / `extra` change (new message, each delta) — but
 * only while the user was already near the bottom, so scrolling up to re-read isn't hijacked.
 * Returns a function that re-engages following (used when the user sends a message).
 */
function useFollowBottom(content: unknown, extra: unknown) {
  const followingRef = useRef(true);

  useEffect(() => {
    const measure = () => {
      const doc = document.documentElement;
      followingRef.current = doc.scrollHeight - (window.scrollY + window.innerHeight) < NEAR_BOTTOM_PX;
    };
    window.addEventListener("scroll", measure, { passive: true });
    window.addEventListener("resize", measure);
    return () => {
      window.removeEventListener("scroll", measure);
      window.removeEventListener("resize", measure);
    };
  }, []);

  // Layout effect: scroll before paint so a growing answer doesn't visibly jump.
  useLayoutEffect(() => {
    if (followingRef.current) window.scrollTo({ top: document.documentElement.scrollHeight });
  }, [content, extra]);

  return useCallback(() => {
    followingRef.current = true;
  }, []);
}

function StateCard({ icon, children }: { icon?: string; children: ReactNode }) {
  return (
    <div className={icon ? "card state-card state-card-error" : "card state-card"}>
      {icon ? (
        <span className="state-icon" aria-hidden>
          {icon}
        </span>
      ) : (
        <span className="spinner" aria-hidden />
      )}
      {children}
    </div>
  );
}

function ClearChatButton({ disabled, onClear }: { disabled: boolean; onClear: () => Promise<void> }) {
  const [confirming, setConfirming] = useState(false);
  const [clearing, setClearing] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleClear = async () => {
    setClearing(true);
    setError(null);
    try {
      await onClear();
      setConfirming(false);
    } catch (err) {
      setError(err instanceof ApiError ? chatErrorMessage(err) : "Could not clear the chat.");
    } finally {
      setClearing(false);
    }
  };

  return (
    <div className="chat-clear">
      {confirming ? (
        <div className="pack-button-row">
          <span className="hint">Clear this conversation?</span>
          <button type="button" className="danger pack-small-button" disabled={clearing} onClick={handleClear}>
            {clearing ? "Clearing…" : "Yes, clear"}
          </button>
          <button
            type="button"
            className="secondary pack-small-button"
            disabled={clearing}
            autoFocus
            onClick={() => setConfirming(false)}
          >
            Cancel
          </button>
        </div>
      ) : (
        <button
          type="button"
          className="secondary pack-small-button"
          disabled={disabled}
          onClick={() => {
            setError(null);
            setConfirming(true);
          }}
        >
          Clear chat
        </button>
      )}
      {error && (
        <p className="error-text" role="alert">
          {error}
        </p>
      )}
    </div>
  );
}

function PackChat({ packId }: { packId: number }) {
  const { token, guest } = useAuth();
  // Guests can't own packs — skip the requests and show the sign-up CTA instead.
  const { pack, items, quota, loadError, reload, streaming, failure, announcement, send, stop, retry, clear } =
    usePackChat(guest ? null : token, packId);
  const follow = useFollowBottom(items, failure);

  const handleSend = (text: string) => {
    follow();
    void send(text);
  };

  let body: ReactNode;
  if (guest) {
    body = (
      <div className="empty-state">
        <span className="empty-state-icon">📚</span>
        <p>Study packs are for signed-up users — create a free account to upload and chat with your documents.</p>
        <Link to="/register" className="button primary">
          Sign up
        </Link>
      </div>
    );
  } else if (loadError) {
    body = (
      <StateCard icon="⚠️">
        <p className="state-message">{loadError.message}</p>
        {loadError.notFound ? (
          <Link to="/packs" className="state-card-link">
            Back to your study packs
          </Link>
        ) : (
          <button type="button" className="secondary" onClick={reload}>
            Try again
          </button>
        )}
      </StateCard>
    );
  } else if (!pack || !items) {
    body = (
      <StateCard>
        <p className="state-message">Loading the chat…</p>
      </StateCard>
    );
  } else if (pack.status !== "READY") {
    body = (
      <StateCard icon={isPackInFlight(pack.status) ? "⏳" : "⚠️"}>
        <p className="state-message">
          {isPackInFlight(pack.status)
            ? "This pack is still being processed — you can chat with it once it's ready."
            : packErrorMessage(pack)}
        </p>
        <Link to="/packs" className="state-card-link">
          Back to your study packs
        </Link>
      </StateCard>
    );
  } else {
    const exhausted = isQuotaExhausted(quota);
    const failedIndex = failure ? items.findIndex((i) => i.key === failure.userKey) : -1;
    const errorBlock = failure && (
      <div className="chat-error" role="alert">
        <p>{failure.message}</p>
        {failure.retryable && (
          <button type="button" className="secondary pack-small-button" disabled={streaming} onClick={retry}>
            Retry
          </button>
        )}
      </div>
    );

    body = (
      <>
        {items.length === 0 && !failure ? (
          <div className="empty-state chat-empty">
            <span className="empty-state-icon">💬</span>
            <p>Ask anything about this document — answers cite the pages they come from.</p>
            <div className="chat-examples">
              {CHAT_EXAMPLE_PROMPTS.map((prompt) => (
                <button
                  key={prompt}
                  type="button"
                  className="secondary chat-example"
                  disabled={exhausted || streaming}
                  onClick={() => handleSend(prompt)}
                >
                  {prompt}
                </button>
              ))}
            </div>
          </div>
        ) : (
          <section aria-label="Conversation">
            <ol className="chat-messages">
              {items.map((item, index) => (
                <li key={item.key} className={item.role === "USER" ? "chat-row chat-row-user" : "chat-row"}>
                  <ChatMessage item={item} />
                  {index === failedIndex && errorBlock}
                </li>
              ))}
            </ol>
          </section>
        )}

        <div className="chat-composer">
          {exhausted && <p className="keyless-warning">{quotaResetMessage()}</p>}
          <ChatInput onSend={handleSend} onStop={stop} streaming={streaming} disabled={exhausted} />
        </div>
      </>
    );
  }

  const ready = pack?.status === "READY" && !!items;

  return (
    <>
      <Navbar />
      <div className="page chat-page">
        <div className="chat-header">
          <Link to="/packs" className="chat-back">
            <span aria-hidden>←</span> Study packs
          </Link>
          <div className="chat-header-main">
            <div className="chat-header-title">
              <p className="eyebrow">Chat</p>
              <h1 className="pack-wrap">{pack?.title ?? "Study pack"}</h1>
              {pack && (
                <div className="session-meta pack-meta">
                  <span className="pack-wrap">{pack.fileName}</span>
                  {pack.pageCount !== null && <span>{pack.pageCount} pages</span>}
                </div>
              )}
            </div>
            {ready && <ClearChatButton disabled={streaming || items.length === 0} onClear={clear} />}
          </div>
          {ready && quota && <QuotaMeter quota={quota} />}
        </div>

        {body}

        <div className="visually-hidden" role="status" aria-live="polite">
          {announcement}
        </div>
      </div>
    </>
  );
}

export default function PackChatPage() {
  const { packId } = useParams<{ packId: string }>();
  const id = Number(packId);

  if (!Number.isInteger(id) || id <= 0) {
    return (
      <>
        <Navbar />
        <div className="page">
          <StateCard icon="⚠️">
            <p className="state-message">This study pack doesn't exist or was deleted.</p>
            <Link to="/packs" className="state-card-link">
              Back to your study packs
            </Link>
          </StateCard>
        </div>
      </>
    );
  }

  // Keyed by pack so navigating between packs remounts with fresh state (and aborts the old stream).
  return <PackChat key={id} packId={id} />;
}
