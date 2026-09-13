import { useSpeechToText } from "../hooks/useSpeechToText";

interface MicButtonProps {
  onTranscript: (text: string) => void;
  disabled?: boolean;
}

/** Speaks-your-answer affordance for conceptual questions — not shown for coding questions,
 *  where the code editor itself is the live-coding answer surface. */
export function MicButton({ onTranscript, disabled }: MicButtonProps) {
  const { isListening, isSupported, error, start, stop } = useSpeechToText({
    onFinalTranscript: (text) => onTranscript(text),
  });

  if (!isSupported) return null;

  return (
    <span className="mic-button-wrap">
      <button
        type="button"
        className={`mic-button${isListening ? " listening" : ""}`}
        onClick={isListening ? stop : start}
        disabled={disabled}
        aria-pressed={isListening}
        aria-label={isListening ? "Stop recording" : "Speak your answer"}
        title={isListening ? "Stop recording" : "Speak your answer"}
      >
        <svg viewBox="0 0 24 24" width="16" height="16" fill="currentColor" aria-hidden="true">
          <path d="M12 15a3 3 0 0 0 3-3V6a3 3 0 0 0-6 0v6a3 3 0 0 0 3 3Z" />
          <path d="M19 11a1 1 0 1 0-2 0 5 5 0 0 1-10 0 1 1 0 1 0-2 0 7 7 0 0 0 6 6.92V20H9a1 1 0 1 0 0 2h6a1 1 0 1 0 0-2h-2v-2.08A7 7 0 0 0 19 11Z" />
        </svg>
        {isListening ? "Listening…" : "Speak"}
      </button>
      {error && <span className="mic-error">{error}</span>}
    </span>
  );
}
