package com.mockinterview.backend.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CitationParserTest {

    @Test
    void collectsDistinctValidMarkersInAscendingOrder() {
        String answer = "Atomicity means all or nothing [2]. Durability survives crashes [1][2], see also [3].";
        assertThat(CitationParser.cited(answer, 3)).containsExactly(1, 2, 3);
    }

    @Test
    void acceptsCommaGroupedMarkers() {
        assertThat(CitationParser.cited("Both levels differ [1, 3] and [2,4].", 4)).containsExactly(1, 2, 3, 4);
    }

    @Test
    void dropsNumbersOutsideTheSourceRange() {
        // [0] and [7] don't exist with 3 sources; [2024] is a year, not a citation.
        assertThat(CitationParser.cited("See [0], [7] and the [2024] edition, but mostly [3].", 3)).containsExactly(3);
    }

    @Test
    void noMarkersOrNoAnswerMeansNothingCited() {
        assertThat(CitationParser.cited("The document doesn't cover this.", 5)).isEmpty();
        assertThat(CitationParser.cited(null, 5)).isEmpty();
        assertThat(CitationParser.cited("An array a[i] is not a citation [x].", 5)).isEmpty();
    }

    @Test
    void stripMarkersRemovesCitationsAndTheSpaceBeforePunctuation() {
        assertThat(CitationParser.stripMarkers("Locks are held until commit [2]. Deadlocks abort a victim [1, 3]."))
                .isEqualTo("Locks are held until commit. Deadlocks abort a victim.");
    }
}
