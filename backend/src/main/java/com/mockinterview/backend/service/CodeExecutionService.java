package com.mockinterview.backend.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mockinterview.backend.dto.CodeRunResponse;
import com.mockinterview.backend.entity.Question;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs candidate-submitted code against a question's stored test cases via a self-hosted Judge0
 * instance (https://github.com/judge0/judge0, CE, run locally via judge0/docker-compose.yml —
 * see judge0/README.md). Self-hosted rather than the free public Piston API this originally used:
 * Piston's public API went whitelist-only on 2026-02-15, so a local Judge0 container is now the
 * only free, always-available option without an approval process or a paid third-party key.
 *
 * Known limitation on Docker Desktop for Windows (WSL2): Judge0's isolate sandbox needs cgroup v1,
 * which the default WSL2 kernel doesn't provide (cgroup v2 only) — see judge0/README.md for the
 * fix. Submitting a coding answer still works fully either way (the LLM evaluates it regardless);
 * only this optional "try it before submitting" Run Code path is affected.
 *
 * Candidate programs are expected to read input from stdin and write output to stdout — the
 * classic online-judge contract — rather than implementing a specific function signature, so no
 * per-language harness/boilerplate is needed. Question.ioFormat describes the exact contract to
 * the candidate. Judge0's own expected-output comparison (its Accepted/Wrong Answer statuses) is
 * deliberately not used — expectedOutput is compared here instead, the same normalize()-based
 * comparison regardless of which execution backend is behind it.
 */
@Service
public class CodeExecutionService {

    private static final Logger log = LoggerFactory.getLogger(CodeExecutionService.class);
    private static final long LANGUAGE_CACHE_TTL_MILLIS = TimeUnit.HOURS.toMillis(6);
    private static final int STATUS_COMPILATION_ERROR = 6;

    // Judge0 language names follow "<Name> (<version details>)" — matching the prefix up to "("
    // avoids e.g. "java" wrongly matching "JavaScript (...)".
    private static final Map<String, String> LANGUAGE_NAME_PREFIX = Map.of(
            "java", "Java (",
            "python", "Python (",
            "javascript", "JavaScript (",
            "typescript", "TypeScript (",
            "cpp", "C++ (",
            "csharp", "C# (",
            "go", "Go ("
    );

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private volatile List<Judge0Language> cachedLanguages;
    private volatile long cachedAt;

    public CodeExecutionService(@Value("${judge0.base-url:http://localhost:2358}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    public CodeRunResponse run(String language, String code, List<Question.TestCase> testCases) {
        int languageId = resolveLanguageId(language);
        List<CodeRunResponse.TestCaseResult> results = new ArrayList<>();
        String compileError = null;

        for (Question.TestCase tc : testCases) {
            if (compileError != null) {
                results.add(new CodeRunResponse.TestCaseResult(tc.getInput(), tc.getExpectedOutput(), null, false, null));
                continue;
            }

            Judge0SubmissionResponse response = submit(languageId, code, tc.getInput());
            if (response.status() != null && response.status().id() == STATUS_COMPILATION_ERROR) {
                compileError = response.compileOutput();
                results.add(new CodeRunResponse.TestCaseResult(tc.getInput(), tc.getExpectedOutput(), null, false, null));
                continue;
            }

            String actual = normalize(response.stdout());
            boolean passed = actual.equals(normalize(tc.getExpectedOutput()));
            results.add(new CodeRunResponse.TestCaseResult(tc.getInput(), tc.getExpectedOutput(), actual, passed, response.stderr()));
        }

        return new CodeRunResponse(results, compileError);
    }

    private Judge0SubmissionResponse submit(int languageId, String code, String stdin) {
        Judge0SubmissionRequest request = new Judge0SubmissionRequest(code, languageId, stdin != null ? stdin : "");
        try {
            String json = objectMapper.writeValueAsString(request);
            return restClient.post()
                    .uri("/submissions?base64_encoded=false&wait=true")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(json)
                    .retrieve()
                    .body(Judge0SubmissionResponse.class);
        } catch (Exception e) {
            log.warn("Judge0 /submissions call failed", e);
            throw new IllegalStateException(
                    "Code execution service is unavailable — make sure the local Judge0 container is running (see judge0/README.md).", e);
        }
    }

    private synchronized int resolveLanguageId(String language) {
        String prefix = LANGUAGE_NAME_PREFIX.get(language.toLowerCase());
        if (prefix == null) {
            throw new IllegalArgumentException("Unsupported language: " + language);
        }
        return getLanguages().stream()
                .filter(l -> l.name() != null && l.name().startsWith(prefix))
                .findFirst()
                .map(Judge0Language::id)
                .orElseThrow(() -> new IllegalStateException("Judge0 has no installed language matching: " + language));
    }

    private List<Judge0Language> getLanguages() {
        if (cachedLanguages == null || System.currentTimeMillis() - cachedAt > LANGUAGE_CACHE_TTL_MILLIS) {
            try {
                cachedLanguages = restClient.get().uri("/languages")
                        .retrieve()
                        .body(new ParameterizedTypeReference<List<Judge0Language>>() {
                        });
                cachedAt = System.currentTimeMillis();
            } catch (Exception e) {
                log.warn("Judge0 /languages call failed", e);
                throw new IllegalStateException(
                        "Code execution service is unavailable — make sure the local Judge0 container is running (see judge0/README.md).", e);
            }
        }
        return cachedLanguages;
    }

    /** Trims the whole string and each line, so trailing whitespace/newline differences don't fail an otherwise-correct answer. */
    private String normalize(String s) {
        if (s == null) return "";
        return String.join("\n", s.strip().lines().map(String::strip).toList());
    }

    private record Judge0Language(int id, String name) {
    }

    private record Judge0Status(int id, String description) {
    }

    private record Judge0SubmissionRequest(
            @JsonProperty("source_code") String sourceCode,
            @JsonProperty("language_id") int languageId,
            String stdin) {
    }

    private record Judge0SubmissionResponse(
            String stdout,
            String stderr,
            @JsonProperty("compile_output") String compileOutput,
            String message,
            Judge0Status status) {
    }
}
