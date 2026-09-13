CREATE TABLE tokens (
    id BIGINT NOT NULL AUTO_INCREMENT,
    token_value VARCHAR(512) NOT NULL,
    revoked BOOLEAN NOT NULL DEFAULT FALSE,
    user_id BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_tokens_token_value UNIQUE (token_value),
    CONSTRAINT fk_tokens_user FOREIGN KEY (user_id) REFERENCES users (id)
);
