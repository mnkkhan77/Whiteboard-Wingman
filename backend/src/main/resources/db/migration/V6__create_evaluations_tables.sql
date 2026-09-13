CREATE TABLE evaluations (
    id BIGINT NOT NULL AUTO_INCREMENT,
    answer_id BIGINT NOT NULL,
    score INT NOT NULL,
    correctness VARCHAR(20) NOT NULL,
    feedback ${clob_type} NOT NULL,
    recommended_next_difficulty VARCHAR(10) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_evaluations_answer UNIQUE (answer_id),
    CONSTRAINT fk_evaluations_answer FOREIGN KEY (answer_id) REFERENCES answers (id)
);

CREATE TABLE evaluation_strengths (
    evaluation_id BIGINT NOT NULL,
    strength VARCHAR(2000),
    CONSTRAINT fk_evaluation_strengths_evaluation FOREIGN KEY (evaluation_id) REFERENCES evaluations (id)
);

CREATE TABLE evaluation_weaknesses (
    evaluation_id BIGINT NOT NULL,
    weakness VARCHAR(2000),
    CONSTRAINT fk_evaluation_weaknesses_evaluation FOREIGN KEY (evaluation_id) REFERENCES evaluations (id)
);
