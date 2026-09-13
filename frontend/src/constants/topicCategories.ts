import type { Category } from "../types/api";

export const CATEGORY_META: Record<Category, { icon: string; label: string }> = {
  JAVA_BACKEND: { icon: "☕", label: "Java Backend" },
  REACT_FRONTEND: { icon: "⚛️", label: "React Frontend" },
  AI_ENGINEERING: { icon: "🤖", label: "AI Engineering" },
};
