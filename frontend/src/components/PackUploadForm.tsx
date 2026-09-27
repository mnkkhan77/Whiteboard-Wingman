import { useState, type FormEvent } from "react";
import { PackDropzone } from "./PackDropzone";
import { ApiError } from "../api/client";
import { isAtPackLimit, uploadErrorMessage, validatePackFile } from "../utils/packs";
import type { PackLimitsDto } from "../types/api";

interface PackUploadFormProps {
  limits: PackLimitsDto;
  onUpload: (file: File, title: string, onProgress: (percent: number) => void) => Promise<void>;
}

export function PackUploadForm({ limits, onUpload }: PackUploadFormProps) {
  const [file, setFile] = useState<File | null>(null);
  const [title, setTitle] = useState("");
  const [fileError, setFileError] = useState<string | null>(null);
  const [uploadError, setUploadError] = useState<string | null>(null);
  const [uploading, setUploading] = useState(false);
  const [progress, setProgress] = useState(0);

  const chooseFile = (picked: File) => {
    setUploadError(null);
    setFile(picked);
    setFileError(validatePackFile(picked, limits));
  };

  const clearFile = () => {
    setFile(null);
    setFileError(null);
  };

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    if (!file) return;
    const problem = validatePackFile(file, limits);
    if (problem) {
      setFileError(problem);
      return;
    }
    setUploading(true);
    setProgress(0);
    setUploadError(null);
    try {
      await onUpload(file, title, setProgress);
      clearFile();
      setTitle("");
    } catch (err) {
      setUploadError(err instanceof ApiError ? uploadErrorMessage(err, limits) : "Upload failed. Please try again.");
    } finally {
      setUploading(false);
    }
  };

  return (
    <form className="card pack-card" onSubmit={handleSubmit}>
      <h2>Upload a document</h2>
      {isAtPackLimit(limits) ? (
        <p className="keyless-warning">
          You've used all {limits.maxPacks} study packs on the {limits.tier} plan. Delete a pack below to upload a new
          one.
        </p>
      ) : (
        <>
          <PackDropzone file={file} limits={limits} disabled={uploading} onFile={chooseFile} onClear={clearFile} />

          <label>
            <span>
              Title <span className="hint">(optional — defaults to the file name)</span>
            </span>
            <input
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              placeholder="e.g. Operating Systems notes"
              maxLength={200}
              disabled={uploading}
            />
          </label>

          {(fileError || uploadError) && (
            <p className="error-text" role="alert">
              {fileError ?? uploadError}
            </p>
          )}

          {uploading && (
            <div className="pack-upload-progress">
              <div
                className="progress-bar"
                role="progressbar"
                aria-label="Upload progress"
                aria-valuemin={0}
                aria-valuemax={100}
                aria-valuenow={progress}
              >
                <div className="progress-bar-fill" style={{ width: `${progress}%` }} />
              </div>
              <p className="progress-label">
                {progress < 100 ? `Uploading… ${progress}%` : "Upload complete — queuing for processing…"}
              </p>
            </div>
          )}

          <button type="submit" className="primary start-cta" disabled={!file || !!fileError || uploading}>
            {uploading ? "Uploading…" : "Upload"}
          </button>
        </>
      )}
    </form>
  );
}
