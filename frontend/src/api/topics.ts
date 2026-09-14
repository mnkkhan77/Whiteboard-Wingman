import { apiFetch } from "./client";
import type { LlmCreds } from "./sessions";
import type { TopicRecommendationResponse, TopicsByCategory } from "../types/api";

export function listTopics(token: string) {
  return apiFetch<TopicsByCategory>("/topics", { token });
}

/** Resume/JD-tailored topic suggestion — requires an LLM key (no non-LLM fallback server-side). */
export function recommendTopic(creds: LlmCreds, resumeOrJdText: string) {
  return apiFetch<TopicRecommendationResponse>("/topics/recommend", {
    method: "POST",
    token: creds.token,
    llmKey: creds.llmKey,
    llmProvider: creds.llmProvider,
    llmModel: creds.llmModel,
    body: { resumeOrJdText },
  });
}
