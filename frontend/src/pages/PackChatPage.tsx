import { useCallback, useEffect, useLayoutEffect, useRef, type ReactNode } from "react";
import { useAuth } from "../context/AuthContext";
import { Navbar } from "../components/Navbar";
import { ChatMessage } from "../components/ChatMessage";
import { ChatInput } from "../components/ChatInput";
import { QuotaMeter } from "../components/QuotaMeter";
import { ConfirmAction } from "../components/ConfirmAction";
import { StateCard } from "../components/StateCard";
import { BackToPacksLink, PackGuestNotice, PackNotReadyCard } from "../components/PackStates";
import { PackPageHeader } from "../components/PackPageHeader";
import { PackRoute } from "../components/PackRoute";
import { usePackChat } from "../hooks/usePackChat";
import { ApiError } from "../api/client";
import { CHAT_EXAMPLE_PROMPTS, chatErrorMessage, isQuotaExhausted, quotaResetMessage } from "../utils/chat";

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

function ClearChatButton({ disabled, onClear }: { disabled: boolean; onClear: () => Promise<void> }) {
  return (
    <div className="chat-clear">
      <ConfirmAction
        label="Clear chat"
        prompt="Clear this conversation?"
        confirmLabel="Yes, clear"
        busyLabel="Clearing…"
        disabled={disabled}
        onConfirm={onClear}
        errorMessage={(err) => (err instanceof ApiError ? chatErrorMessage(err) : "Could not clear the chat.")}
      />
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
      <PackGuestNotice message="Study packs are for signed-up users — create a free account to upload and chat with your documents." />
    );
  } else if (loadError) {
    body = (
      <StateCard icon="⚠️">
        <p className="state-message">{loadError.message}</p>
        {loadError.notFound ? (
          <BackToPacksLink />
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
    body = <PackNotReadyCard pack={pack} activity="chat with it" />;
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
        <PackPageHeader
          eyebrow="Chat"
          pack={pack}
          actions={ready && <ClearChatButton disabled={streaming || items.length === 0} onClear={clear} />}
        >
          {ready && quota && <QuotaMeter quota={quota} />}
        </PackPageHeader>

        {body}

        <div className="visually-hidden" role="status" aria-live="polite">
          {announcement}
        </div>
      </div>
    </>
  );
}

export default function PackChatPage() {
  // Keyed by pack so navigating between packs remounts with fresh state (and aborts the old stream).
  return <PackRoute render={(id) => <PackChat key={id} packId={id} />} />;
}
