CREATE TABLE reports (
    id BIGINT NOT NULL AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    overall_score INT NOT NULL,
    summary_text ${clob_type},
    question_count INT NOT NULL,
    average_difficulty_reached DOUBLE NOT NULL,
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_reports_session UNIQUE (session_id),
    CONSTRAINT fk_reports_session FOREIGN KEY (session_id) REFERENCES interview_sessions (id)
);

CREATE TABLE report_strong_topics (
    report_id BIGINT NOT NULL,
    topic VARCHAR(255),
    CONSTRAINT fk_report_strong_topics_report FOREIGN KEY (report_id) REFERENCES reports (id)
);

CREATE TABLE report_weak_topics (
    report_id BIGINT NOT NULL,
    topic VARCHAR(255),
    CONSTRAINT fk_report_weak_topics_report FOREIGN KEY (report_id) REFERENCES reports (id)
);
