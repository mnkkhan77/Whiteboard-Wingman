import { useCallback, useEffect, useRef, useState } from "react";

// Minimal shape of the Web Speech API's SpeechRecognition — no @types package ships one, and
// pulling in a whole polyfill/type library for a handful of fields isn't worth it.
interface SpeechRecognitionResultLike {
  isFinal: boolean;
  [index: number]: { transcript: string };
}
interface SpeechRecognitionEventLike {
  resultIndex: number;
  results: ArrayLike<SpeechRecognitionResultLike>;
}
interface SpeechRecognitionLike extends EventTarget {
  continuous: boolean;
  interimResults: boolean;
  lang: string;
  start(): void;
  stop(): void;
  onresult: ((event: SpeechRecognitionEventLike) => void) | null;
  onerror: ((event: { error: string }) => void) | null;
  onend: (() => void) | null;
}

interface UseSpeechToTextOptions {
  onFinalTranscript: (text: string) => void;
}

/**
 * Chrome/Edge's SpeechRecognition is NOT fully on-device — it streams audio to Google's speech
 * servers for transcription, so it needs real outbound network access to reach them. A "network"
 * error here almost always means that connection failed (offline, a VPN/proxy, a firewall, or a
 * privacy/ad-block extension blocking Google's endpoints) — not a bug in this app.
 */
function describeSpeechError(code: string): string {
  switch (code) {
    case "not-allowed":
    case "service-not-allowed":
      return "Microphone permission was denied — allow it in your browser's site settings and try again.";
    case "network":
      return "Couldn't reach the speech recognition service. Chrome sends audio to Google's servers to transcribe it, so this needs real internet access — check your connection, VPN, or any ad-blocker/privacy extension that might be blocking it. You can still type your answer.";
    case "no-speech":
      return "Didn't hear anything — try again.";
    case "audio-capture":
      return "No microphone was found.";
    default:
      return `Speech recognition error: ${code}`;
  }
}

/**
 * Free, in-browser speech-to-text via the Web Speech API (Chrome/Edge; no external API, no cost).
 * Not supported in every browser (notably Firefox) — callers must check `isSupported` and degrade
 * gracefully (hide/disable the mic button) rather than assume it's always available.
 */
export function useSpeechToText({ onFinalTranscript }: UseSpeechToTextOptions) {
  const [isListening, setIsListening] = useState(false);
  const [isSupported, setIsSupported] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const recognitionRef = useRef<SpeechRecognitionLike | null>(null);
  const onFinalTranscriptRef = useRef(onFinalTranscript);
  onFinalTranscriptRef.current = onFinalTranscript;

  useEffect(() => {
    const SpeechRecognitionCtor =
      (window as unknown as { SpeechRecognition?: new () => SpeechRecognitionLike }).SpeechRecognition ??
      (window as unknown as { webkitSpeechRecognition?: new () => SpeechRecognitionLike }).webkitSpeechRecognition;

    if (!SpeechRecognitionCtor) {
      setIsSupported(false);
      return;
    }

    const recognition = new SpeechRecognitionCtor();
    recognition.continuous = true;
    recognition.interimResults = true;
    recognition.lang = "en-US";

    recognition.onresult = (event) => {
      let finalText = "";
      for (let i = event.resultIndex; i < event.results.length; i++) {
        const result = event.results[i];
        if (result.isFinal) finalText += result[0].transcript;
      }
      if (finalText.trim()) onFinalTranscriptRef.current(finalText.trim());
    };
    recognition.onerror = (event) => {
      setError(describeSpeechError(event.error));
      setIsListening(false);
    };
    recognition.onend = () => setIsListening(false);

    recognitionRef.current = recognition;
    return () => recognition.stop();
  }, []);

  const start = useCallback(() => {
    setError(null);
    try {
      recognitionRef.current?.start();
      setIsListening(true);
    } catch {
      // start() throws if a recognition session is already active — ignore.
    }
  }, []);

  const stop = useCallback(() => {
    recognitionRef.current?.stop();
    setIsListening(false);
  }, []);

  return { isListening, isSupported, error, start, stop };
}
