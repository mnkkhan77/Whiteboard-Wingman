import Editor from "@monaco-editor/react";
import { useTheme } from "../context/ThemeContext";

const LANGUAGES = [
  { value: "java", label: "Java" },
  { value: "python", label: "Python" },
  { value: "javascript", label: "JavaScript" },
  { value: "typescript", label: "TypeScript" },
  { value: "cpp", label: "C++" },
  { value: "csharp", label: "C#" },
  { value: "go", label: "Go" },
] as const;

interface CodeEditorPaneProps {
  value: string;
  onChange: (value: string) => void;
  language: string;
  onLanguageChange: (language: string) => void;
  disabled?: boolean;
  label?: string;
  height?: string;
}

/** Monaco (VS Code's editor, MIT-licensed) — free, and ships syntax highlighting for every
 *  language in LANGUAGES out of the box, so no per-language plugin/config is needed. */
export function CodeEditorPane({
  value,
  onChange,
  language,
  onLanguageChange,
  disabled,
  label = "Code",
  height = "240px",
}: CodeEditorPaneProps) {
  const { theme } = useTheme();

  return (
    <div className="code-editor-pane">
      <div className="code-editor-toolbar">
        <span>{label}</span>
        <select
          value={language}
          onChange={(e) => onLanguageChange(e.target.value)}
          disabled={disabled}
          aria-label="Code language"
        >
          {LANGUAGES.map((l) => (
            <option key={l.value} value={l.value}>
              {l.label}
            </option>
          ))}
        </select>
      </div>
      <div className="code-editor-monaco">
        <Editor
          height={height}
          language={language}
          value={value}
          onChange={(v) => onChange(v ?? "")}
          theme={theme === "dark" ? "vs-dark" : "vs"}
          options={{
            minimap: { enabled: false },
            fontSize: 14,
            fontFamily: "'JetBrains Mono', Consolas, 'Liberation Mono', monospace",
            automaticLayout: true,
            readOnly: disabled,
            scrollBeyondLastLine: false,
            padding: { top: 12, bottom: 12 },
          }}
        />
      </div>
    </div>
  );
}
