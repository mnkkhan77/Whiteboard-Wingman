CREATE TABLE questions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    sequence_number INT NOT NULL,
    topic VARCHAR(30) NOT NULL,
    difficulty VARCHAR(10) NOT NULL,
    prompt_text ${clob_type} NOT NULL,
    source_chunk_id VARCHAR(255),
    question_type VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_questions_session FOREIGN KEY (session_id) REFERENCES interview_sessions (id)
);
