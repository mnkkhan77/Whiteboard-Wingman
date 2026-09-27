ALTER TABLE questions ADD COLUMN correct_option_index INT;
ALTER TABLE questions ADD COLUMN explanation TEXT;
ALTER TABLE questions ADD COLUMN io_format TEXT;

CREATE TABLE question_options (
    question_id BIGINT NOT NULL,
    option_index INT NOT NULL,
    option_text VARCHAR(1000) NOT NULL,
    CONSTRAINT fk_question_options_question FOREIGN KEY (question_id) REFERENCES questions (id)
);

CREATE TABLE question_test_cases (
    question_id BIGINT NOT NULL,
    case_index INT NOT NULL,
    input TEXT,
    expected_output TEXT NOT NULL,
    CONSTRAINT fk_question_test_cases_question FOREIGN KEY (question_id) REFERENCES questions (id)
);
