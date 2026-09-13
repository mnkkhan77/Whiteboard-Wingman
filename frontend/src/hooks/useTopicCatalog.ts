import { useEffect, useState } from "react";
import { listTopics } from "../api/topics";
import type { Topic, TopicsByCategory, TopicSummary } from "../types/api";

// Module-level cache: the catalog (~160 topics) is the same for every user and rarely changes
// within a session, so fetch it once and share it across every page that needs a topic label.
let cache: TopicsByCategory | null = null;
let inflight: Promise<TopicsByCategory> | null = null;

export function useTopicCatalog(token: string | null) {
  const [data, setData] = useState<TopicsByCategory | null>(cache);

  useEffect(() => {
    if (!token || cache) return;
    if (!inflight) {
      inflight = listTopics(token).then((res) => {
        cache = res;
        return res;
      });
    }
    inflight.then(setData).catch(() => {
      // topic labels are cosmetic — pages fall back to a humanized topic code on failure
    });
  }, [token]);

  const byTopic: Partial<Record<Topic, TopicSummary>> = {};
  if (data) {
    for (const entries of Object.values(data)) {
      for (const entry of entries ?? []) byTopic[entry.topic] = entry;
    }
  }

  function label(topic: Topic): string {
    return byTopic[topic]?.label ?? topic.replaceAll("_", " ");
  }

  return { data, byTopic, label };
}
