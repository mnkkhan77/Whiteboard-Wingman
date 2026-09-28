// Pure helpers for turning topic codes (and pack sessions) into display text. Every page that shows a
// topic or session name goes through these — via useTopicCatalog — so the fallbacks live in one place.
import { CATEGORY_META } from "../constants/topicCategories";
import type { PackSessionFields, Topic, TopicSummary } from "../types/api";

/** Hidden topic of every study-pack quiz session — never in the catalog; show the pack title instead. */
export const STUDY_PACK_TOPIC = "STUDY_PACK";

const STUDY_PACK_LABEL = "Study pack";
const UNKNOWN_TOPIC_LABEL = "Unknown topic";

export type TopicIndex = Partial<Record<Topic, TopicSummary>>;

/** Anything session-shaped: a topic code plus the nullable pack fields. */
export type SessionLike = PackSessionFields & { topic?: Topic | null };

/** "JAVA_COLLECTIONS" → "JAVA COLLECTIONS"; never throws, whatever the backend sends. */
export function humanizeTopic(topic: Topic | null | undefined): string {
  if (typeof topic !== "string" || !topic.trim()) return UNKNOWN_TOPIC_LABEL;
  if (topic === STUDY_PACK_TOPIC) return STUDY_PACK_LABEL;
  return topic.replaceAll("_", " ").trim();
}

/** The catalog label, falling back to a humanized code (catalog not loaded yet, unknown or null topic). */
export function topicLabel(topic: Topic | null | undefined, byTopic: TopicIndex): string {
  return (topic && byTopic[topic]?.label) || humanizeTopic(topic);
}

export function isPackSession(session: SessionLike | null | undefined): boolean {
  return session?.packId != null || session?.topic === STUDY_PACK_TOPIC;
}

/** What to call a session: its pack title when it's a pack quiz, otherwise its topic's label. */
export function sessionLabel(session: SessionLike, byTopic: TopicIndex): string {
  const title = session.packTitle?.trim();
  return title || topicLabel(session.topic, byTopic);
}

/** A topic labeller scoped to one session — the session's STUDY_PACK entries (e.g. in a report's
 *  topic/question breakdown) show its pack title; every other topic gets its normal label. */
export function sessionTopicLabeler(session: SessionLike, byTopic: TopicIndex): (topic: Topic | null | undefined) => string {
  return (topic) => (topic === STUDY_PACK_TOPIC ? sessionLabel(session, byTopic) : topicLabel(topic, byTopic));
}

/** Icon + `topic-icon-*` modifier for a session's avatar. */
export function sessionIcon(session: SessionLike, byTopic: TopicIndex): { icon: string; className: string } {
  if (isPackSession(session)) return { icon: "📚", className: "pack" };
  const category = session.topic ? byTopic[session.topic]?.category : undefined;
  return category
    ? { icon: CATEGORY_META[category].icon, className: category.toLowerCase() }
    : { icon: "💡", className: "default" };
}
