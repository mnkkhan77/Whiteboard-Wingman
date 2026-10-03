import { Link, useParams } from "react-router-dom";
import { useAuth } from "../context/AuthContext";
import { Navbar } from "../components/Navbar";
import { Footer } from "../components/Footer";
import { StateCard } from "../components/StateCard";
import { BackToPacksLink, PackGuestNotice } from "../components/PackStates";
import { ChatMarkdown } from "../components/ChatMarkdown";
import { useCourseLesson } from "../hooks/useCourseLesson";

/** `[n]`-style text never appears in lesson content, so citations are simply never rendered. */
const NO_CITATIONS = () => null;

function CourseLessonView({ packId, lessonId }: { packId: number; lessonId: number }) {
  const { token, guest } = useAuth();
  const { lesson, loading, loadError, reload, completing, completeError, setCompleted } = useCourseLesson(
    guest ? null : token,
    packId,
    lessonId
  );

  return (
    <>
      <Navbar />
      <div className="page">
        <div className="chat-header">
          <Link to={`/packs/${packId}/course`} className="chat-back">
            <span aria-hidden>←</span> Course
          </Link>
        </div>

        {guest ? (
          <PackGuestNotice message="Study packs are for signed-up users — create a free account to study a course." />
        ) : loadError ? (
          <StateCard icon="⚠️">
            <p className="state-message">{loadError}</p>
            <button type="button" className="secondary" onClick={reload}>
              Try again
            </button>
            <BackToPacksLink />
          </StateCard>
        ) : loading || !lesson ? (
          <StateCard>
            <p className="state-message">Writing this lesson's content…</p>
          </StateCard>
        ) : (
          <section className="card course-lesson-page">
            <p className="eyebrow">Lesson</p>
            <h1>{lesson.title}</h1>
            <p className="hint">{lesson.summary}</p>
            {(lesson.sourcePage || lesson.sourceSection) && (
              <p className="hint flashcard-source">
                {lesson.sourceSection ?? "Source"}
                {lesson.sourcePage ? ` (p. ${lesson.sourcePage})` : ""}
              </p>
            )}

            <ChatMarkdown text={lesson.content} sourceCount={0} renderCite={NO_CITATIONS} />

            <div className="pack-button-row">
              <button
                type="button"
                className={lesson.completed ? "secondary pack-small-button" : "primary pack-small-button"}
                disabled={completing}
                onClick={() => void setCompleted(!lesson.completed)}
              >
                {completing ? "Saving…" : lesson.completed ? "Mark incomplete" : "Mark complete"}
              </button>
            </div>
            {completeError && (
              <p className="error-text" role="alert">
                {completeError}
              </p>
            )}
          </section>
        )}
      </div>
      <Footer />
    </>
  );
}

export default function PackCourseLessonPage() {
  const { packId, lessonId } = useParams<{ packId: string; lessonId: string }>();
  const pid = Number(packId);
  const lid = Number(lessonId);

  if (!Number.isInteger(pid) || pid <= 0 || !Number.isInteger(lid) || lid <= 0) {
    return (
      <>
        <Navbar />
        <div className="page">
          <StateCard icon="⚠️">
            <p className="state-message">This lesson doesn't exist or was deleted.</p>
            <BackToPacksLink />
          </StateCard>
        </div>
      </>
    );
  }

  // Keyed by pack+lesson so navigating between lessons remounts with fresh state.
  return <CourseLessonView key={`${pid}-${lid}`} packId={pid} lessonId={lid} />;
}
