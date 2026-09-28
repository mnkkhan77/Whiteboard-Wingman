package com.mockinterview.backend.service;

import com.mockinterview.backend.config.ChatProperties;
import com.mockinterview.backend.dto.ChatSourceDto;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Retrieval half of pack chat: the pack's chunks closest to a question, above the configured
 * similarity threshold, numbered from 1 in relevance order (those numbers are what the answer's
 * [n] citations refer to).
 *
 * The filter matches packId AND ownerId even though the caller already checked ownership of the
 * pack — defense in depth, so a bug upstream can never surface another user's chunks. Both are
 * stored as strings in the vector metadata (PackEmbeddingService.toDocuments).
 */
@Service
@RequiredArgsConstructor
public class PackRetrievalService {

    static final int SNIPPET_CHARS = 300;

    private final VectorStore vectorStore;
    private final ChatProperties chatProperties;

    /** A retrieved chunk: full text for the prompt, metadata + snippet for the client. */
    public record RetrievedChunk(int n, String text, Integer page, Integer pageEnd, String section) {

        public ChatSourceDto toSource() {
            String snippet = text.length() <= SNIPPET_CHARS ? text : text.substring(0, SNIPPET_CHARS).stripTrailing() + "…";
            return new ChatSourceDto(n, page, pageEnd, section, snippet);
        }
    }

    public List<RetrievedChunk> retrieve(long packId, long ownerId, String question) {
        return numbered(search(packId, ownerId, question));
    }

    /**
     * Widens retrieval for a follow-up ("how is that different from a saga?"): the question alone
     * misses whatever "that" refers to, so the previous question is searched together with it and
     * the extra hits are appended after the question's own, deduplicated, up to topK. Only called
     * when the question alone already matched the document — an off-topic question must still
     * short-circuit to "not covered" rather than pull in the previous topic's chunks.
     */
    public List<RetrievedChunk> expandForFollowUp(long packId, long ownerId, List<RetrievedChunk> hits,
                                                  String previousQuestion, String question) {
        Map<String, RetrievedChunk> byText = new LinkedHashMap<>();
        hits.forEach(hit -> byText.put(hit.text(), hit));
        for (RetrievedChunk extra : search(packId, ownerId, previousQuestion + "\n" + question)) {
            if (byText.size() >= chatProperties.topK()) {
                break;
            }
            byText.putIfAbsent(extra.text(), extra);
        }
        return numbered(new ArrayList<>(byText.values()));
    }

    /** Unnumbered (n = 0) hits in relevance order. */
    private List<RetrievedChunk> search(long packId, long ownerId, String query) {
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(chatProperties.topK())
                .similarityThreshold(chatProperties.similarityThreshold())
                .filterExpression(b.and(
                        b.eq("packId", String.valueOf(packId)),
                        b.eq("ownerId", String.valueOf(ownerId))).build())
                .build();
        List<Document> documents = vectorStore.similaritySearch(request);
        List<RetrievedChunk> chunks = new ArrayList<>(documents == null ? 0 : documents.size());
        if (documents != null) {
            for (Document document : documents) {
                if (document.getText() == null || document.getText().isBlank()) {
                    continue;
                }
                Map<String, Object> metadata = document.getMetadata();
                chunks.add(new RetrievedChunk(0, document.getText(),
                        intOrNull(metadata.get("page")), intOrNull(metadata.get("pageEnd")),
                        metadata.get("section") instanceof String s ? s : null));
            }
        }
        return chunks;
    }

    /** Numbers sources 1..n in list order — the numbers the answer's [n] citations refer to. */
    private static List<RetrievedChunk> numbered(List<RetrievedChunk> chunks) {
        List<RetrievedChunk> numbered = new ArrayList<>(chunks.size());
        for (RetrievedChunk c : chunks) {
            numbered.add(new RetrievedChunk(numbered.size() + 1, c.text(), c.page(), c.pageEnd(), c.section()));
        }
        return numbered;
    }

    /** jsonb numbers come back as Integer, Long or Double depending on the JSON reader. */
    private static Integer intOrNull(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }
}
