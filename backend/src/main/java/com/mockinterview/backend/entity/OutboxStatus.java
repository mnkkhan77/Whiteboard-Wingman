package com.mockinterview.backend.entity;

/** PENDING rows are claimed and sent by OutboxPublisher; a row that sends successfully is deleted
 *  (the table is a queue, not a log). FAILED is terminal — app.outbox.max-attempts was reached — and
 *  is left in place for an operator to notice and resend or clean up. */
public enum OutboxStatus {
    PENDING, FAILED
}
