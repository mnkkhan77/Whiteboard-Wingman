import type { ReactNode } from "react";
import { useNavigate } from "react-router-dom";
import { useAuth } from "../context/AuthContext";
import { Navbar } from "../components/Navbar";
import { Footer } from "../components/Footer";
import { QuotaMeter } from "../components/QuotaMeter";
import { StateCard } from "../components/StateCard";
import { BackToPacksLink, PackGuestNotice, PackNotReadyCard } from "../components/PackStates";
import { PackPageHeader } from "../components/PackPageHeader";
import { PackRoute } from "../components/PackRoute";
import { QuizBankCard } from "../components/QuizBankCard";
import { QuizStartForm, type QuizStartValues } from "../components/QuizStartForm";
import { usePackQuiz } from "../hooks/usePackQuiz";
import { isQuotaExhausted } from "../utils/chat";
import { interviewLaunch } from "../utils/interview";
import { quizQuotaMessage } from "../utils/quiz";

function PackQuiz({ packId }: { packId: number }) {
  const { token, guest } = useAuth();
  const navigate = useNavigate();
  // Guests can't own packs — skip the requests and show the sign-up CTA instead.
  const { pack, quota, loadError, reload, generating, generateError, generate, starting, startError, start } =
    usePackQuiz(guest ? null : token, packId);

  const handleStart = async ({ timedMode, ...options }: QuizStartValues) => {
    const res = await start(options);
    // Same hand-off as a topic interview; no `topics` list, so no "Topic X of Y" label.
    if (res) navigate(...interviewLaunch(res, { targetQuestionCount: options.questionCount, timedMode }));
  };

  const exhausted = isQuotaExhausted(quota);
  let body: ReactNode;
  if (guest) {
    body = (
      <PackGuestNotice message="Study packs are for signed-up users — create a free account to upload documents and quiz yourself on them." />
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
        <p className="state-message">Loading the quiz…</p>
      </StateCard>
    );
  } else if (pack.status !== "READY") {
    body = <PackNotReadyCard pack={pack} activity="quiz yourself on it" />;
  } else {
    body = (
      <>
        {exhausted && <p className="keyless-warning">{quizQuotaMessage()}</p>}
        <QuizBankCard
          pack={pack}
          generating={generating}
          error={generateError}
          blocked={exhausted}
          onGenerate={generate}
        />
        {pack.quizStatus === "READY" && (
          <QuizStartForm
            pack={pack}
            starting={starting}
            error={startError}
            disabled={exhausted || generating}
            onStart={(values) => void handleStart(values)}
          />
        )}
      </>
    );
  }

  return (
    <>
      <Navbar />
      <div className="page">
        <PackPageHeader eyebrow="Quiz" pack={pack}>
          {pack?.status === "READY" && quota && <QuotaMeter quota={quota} label="Tokens this month (chat + quizzes)" />}
        </PackPageHeader>
        {body}
      </div>
      <Footer />
    </>
  );
}

export default function PackQuizPage() {
  // Keyed by pack so navigating between packs remounts with fresh state (and stops the old poll).
  return <PackRoute render={(id) => <PackQuiz key={id} packId={id} />} />;
}
