CREATE TABLE answers (
    id BIGINT NOT NULL AUTO_INCREMENT,
    question_id BIGINT NOT NULL,
    answer_text ${clob_type} NOT NULL,
    code_submission ${clob_type},
    submitted_at TIMESTAMP NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_answers_question UNIQUE (question_id),
    CONSTRAINT fk_answers_question FOREIGN KEY (question_id) REFERENCES questions (id)
);
