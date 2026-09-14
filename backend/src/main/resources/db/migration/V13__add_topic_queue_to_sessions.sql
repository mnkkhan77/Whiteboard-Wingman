CREATE TABLE interview_session_topic_queue (
    session_id BIGINT NOT NULL,
    queue_index INT NOT NULL,
    topic VARCHAR(30) NOT NULL,
    CONSTRAINT fk_interview_session_topic_queue_session FOREIGN KEY (session_id) REFERENCES interview_sessions (id)
);
