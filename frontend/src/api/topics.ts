import { apiFetch } from "./client";
import type { TopicsByCategory } from "../types/api";

export function listTopics(token: string) {
  return apiFetch<TopicsByCategory>("/topics", { token });
}
