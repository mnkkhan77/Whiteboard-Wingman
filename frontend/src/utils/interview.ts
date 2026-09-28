// The hand-off from a start page (topic interview or pack quiz) to InterviewPage via router state.
import type { QuestionResponse, SessionStartResponse, Topic } from "../types/api";

/** InterviewPage's `location.state` on a fresh start — lost on refresh, when it falls back to
 *  GET /sessions/{id}/current. */
export interface InterviewLaunchState {
  firstQuestion: QuestionResponse;
  targetQuestionCount: number;
  timedMode?: boolean;
  // The ordered topic loop as chosen on the start page, used purely to show "Topic X of Y"; the loop
  // itself advances correctly either way since that's driven by the backend. Absent for pack quizzes.
  topics?: Topic[];
}

/** `navigate(...interviewLaunch(...))` arguments for a just-started session. */
export function interviewLaunch(
  res: SessionStartResponse,
  options: Omit<InterviewLaunchState, "firstQuestion">
): [string, { state: InterviewLaunchState }] {
  return [`/interview/${res.sessionId}`, { state: { firstQuestion: res.firstQuestion, ...options } }];
}
