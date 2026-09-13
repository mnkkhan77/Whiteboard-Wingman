package com.mockinterview.backend.dto;

/** Optional body for POST /sessions/{id}/complete — tabSwitchCount is the frontend's final tally
 *  of tab-visibility-loss events during the interview, recorded on the report as an integrity note. */
public record CompleteSessionRequest(Integer tabSwitchCount) {
}
