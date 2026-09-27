import { useRef, useState, type DragEvent } from "react";
import { formatBytes, formatExtensions } from "../utils/packs";
import type { PackLimitsDto } from "../types/api";

interface PackDropzoneProps {
  file: File | null;
  limits: PackLimitsDto;
  disabled: boolean;
  onFile: (file: File) => void;
  onClear: () => void;
}

/** Drag-and-drop target plus a real "Choose file" button, so keyboard and screen-reader users
 *  never depend on dragging. The native file input stays hidden behind that button. */
export function PackDropzone({ file, limits, disabled, onFile, onClear }: PackDropzoneProps) {
  const [dragActive, setDragActive] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);

  const handleDragOver = (e: DragEvent<HTMLDivElement>) => {
    e.preventDefault();
    if (!disabled) setDragActive(true);
  };

  const handleDragLeave = (e: DragEvent<HTMLDivElement>) => {
    // dragleave also fires when moving onto a child element — only reset when leaving the zone.
    if (!e.currentTarget.contains(e.relatedTarget as Node | null)) setDragActive(false);
  };

  const handleDrop = (e: DragEvent<HTMLDivElement>) => {
    e.preventDefault();
    setDragActive(false);
    const dropped = e.dataTransfer.files?.[0];
    if (!disabled && dropped) onFile(dropped);
  };

  return (
    <div
      className={`pack-dropzone${dragActive ? " pack-dropzone-active" : ""}${disabled ? " pack-dropzone-disabled" : ""}`}
      onDragEnter={handleDragOver}
      onDragOver={handleDragOver}
      onDragLeave={handleDragLeave}
      onDrop={handleDrop}
    >
      <span className="pack-dropzone-icon" aria-hidden>
        📄
      </span>
      {file ? (
        <p className="pack-wrap">
          <strong>{file.name}</strong> <span className="hint">({formatBytes(file.size)})</span>
        </p>
      ) : (
        <p className="pack-wrap">Drag and drop a file here, or</p>
      )}
      <div className="pack-button-row">
        <button type="button" className="secondary" disabled={disabled} onClick={() => inputRef.current?.click()}>
          {file ? "Choose a different file" : "Choose file"}
        </button>
        {file && (
          <button type="button" className="secondary" disabled={disabled} onClick={onClear}>
            Remove
          </button>
        )}
      </div>
      <p className="pack-dropzone-hint">
        {formatExtensions(limits.allowedExtensions)} · up to {formatBytes(limits.maxFileBytes)}
      </p>
      <input
        ref={inputRef}
        type="file"
        hidden
        accept={formatExtensions(limits.allowedExtensions, ",")}
        disabled={disabled}
        onChange={(e) => {
          const picked = e.target.files?.[0];
          // Reset so re-picking the same file (after Remove or an upload) still fires onChange.
          e.target.value = "";
          if (picked) onFile(picked);
        }}
      />
    </div>
  );
}
