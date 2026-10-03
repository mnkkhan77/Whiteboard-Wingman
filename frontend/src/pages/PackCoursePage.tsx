import type { ReactNode } from "react";
import { useAuth } from "../context/AuthContext";
import { Navbar } from "../components/Navbar";
import { Footer } from "../components/Footer";
import { QuotaMeter } from "../components/QuotaMeter";
import { StateCard } from "../components/StateCard";
import { BackToPacksLink, PackGuestNotice, PackNotReadyCard } from "../components/PackStates";
import { PackPageHeader } from "../components/PackPageHeader";
import { PackRoute } from "../components/PackRoute";
import { CourseOutlineCard } from "../components/CourseOutlineCard";
import { CourseModuleList } from "../components/CourseModuleList";
import { usePackCourse } from "../hooks/usePackCourse";
import { isQuotaExhausted } from "../utils/chat";
import { courseQuotaMessage } from "../utils/course";

function PackCourse({ packId }: { packId: number }) {
  const { token, guest } = useAuth();
  // Guests can't own packs — skip the requests and show the sign-up CTA instead.
  const { pack, quota, loadError, reload, generating, generateError, generate, course, courseError, reloadCourse } =
    usePackCourse(guest ? null : token, packId);

  const exhausted = isQuotaExhausted(quota);
  let body: ReactNode;
  if (guest) {
    body = (
      <PackGuestNotice message="Study packs are for signed-up users — create a free account to upload documents and turn them into a course." />
    );
  } else if (loadError) {
    body = (
      <StateCard icon="⚠️">
        <p className="state-message">{loadError.message}</p>
        {loadError.notFound ? (
          <BackToPacksLink />
        ) : (
          <button type="button" className="secondary" onClick={reload}>
            Try again
          </button>
        )}
      </StateCard>
    );
  } else if (!pack) {
    body = (
      <StateCard>
        <p className="state-message">Loading the course…</p>
      </StateCard>
    );
  } else if (pack.status !== "READY") {
    body = <PackNotReadyCard pack={pack} activity="turn it into a course" />;
  } else {
    body = (
      <>
        {exhausted && <p className="keyless-warning">{courseQuotaMessage()}</p>}
        <CourseOutlineCard pack={pack} generating={generating} error={generateError} blocked={exhausted} onGenerate={generate} />
        {pack.courseStatus === "READY" &&
          (courseError ? (
            <StateCard icon="⚠️">
              <p className="state-message">{courseError}</p>
              <button type="button" className="secondary" onClick={reloadCourse}>
                Try again
              </button>
            </StateCard>
          ) : course === null ? (
            <StateCard>
              <p className="state-message">Loading your course…</p>
            </StateCard>
          ) : (
            <CourseModuleList packId={packId} course={course} />
          ))}
      </>
    );
  }

  return (
    <>
      <Navbar />
      <div className="page">
        <PackPageHeader eyebrow="Course" pack={pack}>
          {pack?.status === "READY" && quota && <QuotaMeter quota={quota} label="Tokens this month (chat + quizzes + flashcards + course)" />}
        </PackPageHeader>
        {body}
      </div>
      <Footer />
    </>
  );
}

export default function PackCoursePage() {
  // Keyed by pack so navigating between packs remounts with fresh state (and stops the old poll).
  return <PackRoute render={(id) => <PackCourse key={id} packId={id} />} />;
}
