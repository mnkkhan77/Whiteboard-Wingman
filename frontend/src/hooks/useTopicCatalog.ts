import { useEffect, useState } from "react";
import { listTopics } from "../api/topics";
import {
  sessionIcon,
  sessionLabel,
  sessionTopicLabeler,
  topicLabel,
  type SessionLike,
  type TopicIndex,
} from "../utils/topics";
import type { Topic, TopicsByCategory } from "../types/api";

// Module-level cache: the catalog (~160 topics) is the same for every user and rarely changes
// within a session, so fetch it once and share it across every page that needs a topic label.
let cache: TopicsByCategory | null = null;
let inflight: Promise<TopicsByCategory> | null = null;

// The catalog endpoint (GET /api/topics) is public — token is only passed through when the
// caller happens to have one (it's harmless either way), not required to fetch.
export function useTopicCatalog(token?: string | null) {
  const [data, setData] = useState<TopicsByCategory | null>(cache);

  useEffect(() => {
    if (cache) return;
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

  const byTopic: TopicIndex = {};
  if (data) {
    for (const entries of Object.values(data)) {
      for (const entry of entries ?? []) byTopic[entry.topic] = entry;
    }
  }

  // Display helpers bound to the loaded catalog — see utils/topics.ts for the fallbacks (they never
  // throw on a null/unknown topic, and pack sessions show their pack title).
  return {
    data,
    byTopic,
    /** A bare topic code's label. */
    label: (topic: Topic | null | undefined) => topicLabel(topic, byTopic),
    /** A session's name: pack title for a pack quiz, else its topic label. */
    sessionLabel: (session: SessionLike) => sessionLabel(session, byTopic),
    /** Topic labeller for one session's breakdowns (its STUDY_PACK entries show the pack title). */
    labelerFor: (session: SessionLike) => sessionTopicLabeler(session, byTopic),
    /** Avatar icon + `topic-icon-*` modifier for a session. */
    iconFor: (session: SessionLike) => sessionIcon(session, byTopic),
  };
}
