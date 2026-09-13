package com.mockinterview.backend.dto;

import java.util.List;

/**
 * compileError is non-null when the submission failed to compile — every test case after the
 * first compile attempt is reported un-run (actualOutput null, passed false) rather than repeating
 * the same failed compile call once per test case.
 */
public record CodeRunResponse(
        List<TestCaseResult> results,
        String compileError
) {
    public record TestCaseResult(String input, String expectedOutput, String actualOutput, boolean passed, String stderr) {
    }
}
