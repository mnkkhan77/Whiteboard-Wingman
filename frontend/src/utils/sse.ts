// Incremental Server-Sent Events parser (the `text/event-stream` format from the HTML spec).
// Framework-free so it can be exercised with plain Node. `EventSource` can't POST or send an
// Authorization header, so the chat stream is read with fetch and fed through this instead.

export interface SseEvent {
  /** `event:` field; "message" when the server didn't send one. */
  event: string;
  /** All `data:` lines of the event joined with "\n". */
  data: string;
  /** `id:` field, if the event carried one. */
  id?: string;
}

export interface SseParser {
  /** Feed the next piece of decoded text. Pieces may split lines/events anywhere. */
  push(chunk: string): void;
  /** Call once the stream has ended — flushes a final event the server didn't terminate. */
  end(): void;
}

/**
 * Line endings may be `\n`, `\r\n` or a bare `\r`; a blank line dispatches the event built so far;
 * lines starting with `:` are comments (keep-alives); one space after the colon is stripped;
 * unknown fields (incl. `retry`) are ignored; an event with no `data:` line is not dispatched.
 *
 * One deliberate leniency: `end()` dispatches a trailing event that has data but no terminating
 * blank line (the spec discards it), so a `done` event is never lost to a missing final newline.
 */
export function createSseParser(onEvent: (event: SseEvent) => void): SseParser {
  let buffer = "";
  let eventType = "";
  let dataLines: string[] = [];
  let lastId: string | undefined;
  let sawFirstChunk = false;

  const dispatch = () => {
    if (dataLines.length > 0) {
      onEvent({ event: eventType || "message", data: dataLines.join("\n"), ...(lastId !== undefined ? { id: lastId } : {}) });
    }
    eventType = "";
    dataLines = [];
  };

  const processLine = (line: string) => {
    if (line === "") {
      dispatch();
      return;
    }
    if (line.startsWith(":")) return; // comment / keep-alive
    const colon = line.indexOf(":");
    const field = colon === -1 ? line : line.slice(0, colon);
    let value = colon === -1 ? "" : line.slice(colon + 1);
    if (value.startsWith(" ")) value = value.slice(1);
    switch (field) {
      case "event":
        eventType = value;
        break;
      case "data":
        dataLines.push(value);
        break;
      case "id":
        if (!value.includes("\0")) lastId = value;
        break;
      default:
        // "retry" and unknown fields are irrelevant to a fetch-based reader
        break;
    }
  };

  const drainLines = (final: boolean) => {
    let start = 0;
    for (let i = 0; i < buffer.length; i++) {
      const ch = buffer[i];
      if (ch === "\n") {
        processLine(buffer.slice(start, i));
        start = i + 1;
      } else if (ch === "\r") {
        // A "\r" at the very end might be the first half of a "\r\n" split across chunks —
        // wait for the next chunk before deciding (unless the stream is over).
        if (i === buffer.length - 1 && !final) break;
        processLine(buffer.slice(start, i));
        if (buffer[i + 1] === "\n") i++;
        start = i + 1;
      }
    }
    buffer = buffer.slice(start);
  };

  return {
    push(chunk: string) {
      if (!chunk) return;
      if (!sawFirstChunk) {
        sawFirstChunk = true;
        if (chunk.charCodeAt(0) === 0xfeff) chunk = chunk.slice(1); // optional UTF-8 BOM
      }
      buffer += chunk;
      drainLines(false);
    },
    end() {
      drainLines(true);
      if (buffer !== "") {
        processLine(buffer);
        buffer = "";
      }
      dispatch();
    },
  };
}
