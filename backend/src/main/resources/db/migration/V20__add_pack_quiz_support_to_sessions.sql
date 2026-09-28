-- A pack quiz is a normal interview session with topic = 'STUDY_PACK' plus the pack it quizzes on.
-- ON DELETE SET NULL, not CASCADE: deleting a pack must not erase the user's interview history,
-- reports and progress; such a session just loses its pack link (and title).
ALTER TABLE interview_sessions ADD COLUMN pack_id BIGINT;
ALTER TABLE interview_sessions ADD CONSTRAINT fk_interview_sessions_pack
    FOREIGN KEY (pack_id) REFERENCES study_packs (id) ON DELETE SET NULL;
-- Partial: only pack sessions carry a pack_id; serves the FK's SET NULL lookup on pack delete.
CREATE INDEX idx_interview_sessions_pack ON interview_sessions (pack_id) WHERE pack_id IS NOT NULL;

-- Snapshot of the bank question a session question was copied from (like options/explanation for
-- static-bank MCQs), so grading and feedback keep working even if the bank is regenerated or the
-- pack deleted mid-session. NULL for every handbook-topic question.
ALTER TABLE questions ADD COLUMN reference_answer TEXT;
ALTER TABLE questions ADD COLUMN source_page INT;
ALTER TABLE questions ADD COLUMN source_section VARCHAR(500);
