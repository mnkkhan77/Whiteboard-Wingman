package com.mockinterview.backend.service;

import com.mockinterview.backend.service.PackRetrievalService.RetrievedChunk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pack retrieval against real pgvector and the real local all-MiniLM-L6-v2 model: the owner/pack
 * filter really isolates chunks, and app.chat.similarity-threshold really separates answerable
 * from off-topic questions (these scores are the evidence behind the configured value).
 *
 * Same annotations as the controller tests so Spring reuses their cached context.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PackRetrievalServiceIntegrationTest {

    // Far above any id the other tests' packs get, and unique to this class.
    private static final long PACK_A = 910_001;
    private static final long PACK_B = 910_002;
    private static final long PACK_C = 910_003;
    private static final long OWNER_1 = 1_001;
    private static final long OWNER_2 = 1_002;

    @Autowired private PackRetrievalService retrievalService;
    @Autowired private VectorStore vectorStore;

    private final List<String> written = new ArrayList<>();

    @AfterEach
    void removeTestVectors() {
        // The container is shared by the whole run — leftovers would leak into other tests.
        vectorStore.delete(written);
    }

    /** The chapter's chunks as a pack; the section tag says whose copy a hit came from. */
    private void givenPack(long packId, long ownerId, String tag) {
        List<String> chunks = PackChatFixtures.TRANSACTIONS_CHAPTER;
        List<Document> documents = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("source", "pack");
            metadata.put("packId", String.valueOf(packId));
            metadata.put("ownerId", String.valueOf(ownerId));
            metadata.put("chunkIndex", i);
            metadata.put("page", 10 + i);
            metadata.put("pageEnd", 11 + i);
            metadata.put("section", tag + " > chunk " + i);
            metadata.put("elementType", "text");
            String id = PackEmbeddingService.vectorId(packId, i);
            documents.add(Document.builder().id(id).text(chunks.get(i)).metadata(metadata).build());
            written.add(id);
        }
        vectorStore.add(documents);
    }

    @Test
    void answerableQuestionsFindTheRightChunkFirst() {
        givenPack(PACK_A, OWNER_1, "A");

        assertThat(retrievalService.retrieve(PACK_A, OWNER_1, "What does ACID stand for?").get(0).section())
                .isEqualTo("A > chunk 0");
        assertThat(retrievalService.retrieve(PACK_A, OWNER_1,
                "What is the difference between read committed and repeatable read?").get(0).section())
                .isEqualTo("A > chunk 1");
        assertThat(retrievalService.retrieve(PACK_A, OWNER_1, "Explain deadlocks").get(0).section())
                .isEqualTo("A > chunk 3");
        assertThat(retrievalService.retrieve(PACK_A, OWNER_1, "what is MVCC").get(0).section())
                .isEqualTo("A > chunk 4");
        assertThat(retrievalService.retrieve(PACK_A, OWNER_1,
                "How does the write-ahead log help with crash recovery?").get(0).section())
                .isEqualTo("A > chunk 5");
    }

    @Test
    void offTopicQuestionsFindNothingAboveTheThreshold() {
        givenPack(PACK_A, OWNER_1, "A");

        for (String question : List.of("How do I bake sourdough bread?", "What is the capital of France?",
                "How does Kubernetes schedule pods?", "What is a binary search tree?", "What is photosynthesis?",
                "Tell me a joke")) {
            assertThat(retrievalService.retrieve(PACK_A, OWNER_1, question)).as(question).isEmpty();
        }
    }

    @Test
    void aFollowUpAlsoPullsInWhatThePreviousQuestionWasAbout() {
        givenPack(PACK_A, OWNER_1, "A");
        String previous = "Explain deadlocks";
        String followUp = "How does MVCC avoid that?";
        List<RetrievedChunk> own = retrievalService.retrieve(PACK_A, OWNER_1, followUp);
        assertThat(own).isNotEmpty();

        List<RetrievedChunk> expanded = retrievalService.expandForFollowUp(PACK_A, OWNER_1, own, previous, followUp);

        // The follow-up's own hits stay first, the previous topic's chunk is added, nothing twice.
        assertThat(expanded.subList(0, own.size())).extracting(RetrievedChunk::section)
                .containsExactlyElementsOf(own.stream().map(RetrievedChunk::section).toList());
        assertThat(expanded).extracting(RetrievedChunk::section).contains("A > chunk 3", "A > chunk 4").doesNotHaveDuplicates();
        assertThat(expanded).hasSizeLessThanOrEqualTo(6);
        for (int i = 0; i < expanded.size(); i++) {
            assertThat(expanded.get(i).n()).isEqualTo(i + 1);
        }
    }

    @Test
    void onlyTheRequestedPackOfTheRequestingOwnerIsEverSearched() {
        givenPack(PACK_A, OWNER_1, "A");
        givenPack(PACK_B, OWNER_1, "B");       // same owner, other pack
        givenPack(PACK_C, OWNER_2, "C");       // other owner

        List<RetrievedChunk> hits = retrievalService.retrieve(PACK_A, OWNER_1, "Explain deadlocks");
        assertThat(hits).isNotEmpty().allSatisfy(hit -> assertThat(hit.section()).startsWith("A >"));

        // Right pack id, wrong owner: nothing, even though the chunks exist.
        assertThat(retrievalService.retrieve(PACK_C, OWNER_1, "Explain deadlocks")).isEmpty();
        assertThat(retrievalService.retrieve(PACK_A, OWNER_2, "Explain deadlocks")).isEmpty();
    }

    @Test
    void hitsAreNumberedFromOneWithPageMetadataAndASnippet() {
        givenPack(PACK_A, OWNER_1, "A");

        List<RetrievedChunk> hits = retrievalService.retrieve(PACK_A, OWNER_1,
                "What is the difference between read committed and repeatable read?");

        assertThat(hits).hasSizeGreaterThan(1).hasSizeLessThanOrEqualTo(6);
        for (int i = 0; i < hits.size(); i++) {
            assertThat(hits.get(i).n()).isEqualTo(i + 1);
        }
        RetrievedChunk first = hits.get(0);
        assertThat(first.page()).isEqualTo(11);
        assertThat(first.pageEnd()).isEqualTo(12);
        var source = first.toSource();
        assertThat(source.n()).isEqualTo(1);
        assertThat(source.snippet()).hasSizeLessThanOrEqualTo(PackRetrievalService.SNIPPET_CHARS + 1)
                .startsWith("Isolation levels.").endsWith("…");
        assertThat(first.text()).startsWith(source.snippet().substring(0, 50)); // prompt gets the full text
    }
}
