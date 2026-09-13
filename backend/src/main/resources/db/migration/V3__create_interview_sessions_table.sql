CREATE TABLE interview_sessions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    topic VARCHAR(30) NOT NULL,
    starting_difficulty VARCHAR(10) NOT NULL,
    current_difficulty VARCHAR(10) NOT NULL,
    status VARCHAR(20) NOT NULL,
    target_question_count INT NOT NULL,
    questions_asked INT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_interview_sessions_user FOREIGN KEY (user_id) REFERENCES users (id)
);
