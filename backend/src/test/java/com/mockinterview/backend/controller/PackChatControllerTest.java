package com.mockinterview.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mockinterview.backend.dto.ChatStreamEvent;
import com.mockinterview.backend.entity.ChatRole;
import com.mockinterview.backend.entity.PackChatMessage;
import com.mockinterview.backend.entity.StudyPack;
import com.mockinterview.backend.entity.StudyPackStatus;
import com.mockinterview.backend.entity.Tier;
import com.mockinterview.backend.entity.User;
import com.mockinterview.backend.exception.PackChatException;
import com.mockinterview.backend.repository.LlmUsageRepository;
import com.mockinterview.backend.repository.PackChatMessageRepository;
import com.mockinterview.backend.repository.StudyPackRepository;
import com.mockinterview.backend.repository.UserRepository;
import com.mockinterview.backend.service.PackChatFixtures;
import com.mockinterview.backend.service.PackChatService;
import com.mockinterview.backend.service.PackEmbeddingService;
import com.mockinterview.backend.service.ServerChatClientProvider;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Pack chat end to end through the real security chain, PostgreSQL, pgvector and the real local
 * embedding model — only the LLM is faked: ServerChatClientProvider hands out a real ChatClient
 * over {@link FakeChatModel}, whose stream() plays back scripted chunks (text deltas, a final
 * usage-only chunk, or a mid-stream failure), so the SSE sequence and payloads are asserted exactly.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PackChatControllerTest {

    private static final String ANSWERABLE = "What does ACID stand for?";
    private static final String OFF_TOPIC = "How do I bake sourdough bread?";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private StudyPackRepository studyPackRepository;
    @Autowired private PackChatMessageRepository messageRepository;
    @Autowired private LlmUsageRepository llmUsageRepository;
    @Autowired private PackChatService packChatService;
    @Autowired private VectorStore vectorStore;
    @Autowired private EntityManagerFactory entityManagerFactory;

    @MockitoBean private ServerChatClientProvider serverChatClientProvider;

    private final FakeChatModel llm = new FakeChatModel();
    private final List<String> vectorIds = new ArrayList<>();

    private record Registered(String token, User user) {}

    private record SseEvent(String name, JsonNode data) {}

    /** A ChatModel whose stream() is scripted per test and which records every prompt it got. */
    static final class FakeChatModel implements ChatModel {
        final List<Prompt> prompts = new CopyOnWriteArrayList<>();
        volatile Function<Prompt, Flux<ChatResponse>> script = p -> Flux.empty();

        @Override
        public ChatResponse call(Prompt prompt) {
            throw new UnsupportedOperationException("pack chat only streams");
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            prompts.add(prompt);
            return script.apply(prompt);
        }
    }

    private static ChatResponse text(String delta) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(delta))));
    }

    /** The shape of OpenAI/Groq's last chunk with stream_options.include_usage: no choices, just usage. */
    private static ChatResponse usage(int prompt, int completion) {
        return ChatResponse.builder().generations(List.of())
                .metadata(ChatResponseMetadata.builder().usage(new DefaultUsage(prompt, completion)).build())
                .build();
    }

    @BeforeEach
    void wireFakeLlm() {
        when(serverChatClientProvider.require()).thenReturn(ChatClient.create(llm));
    }

    @AfterEach
    void removeTestVectors() {
        vectorStore.delete(vectorIds); // shared container — see PgVectorStoreIntegrationTest
    }

    private Registered register(Tier tier) throws Exception {
        String email = "chat-" + UUID.randomUUID() + "@example.com";
        String body = objectMapper.writeValueAsString(Map.of(
                "email", email, "password", "password123", "displayName", "Chat User"));
        String response = mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        User user = userRepository.findByEmail(email).orElseThrow();
        if (tier != Tier.FREE) {
            user.setTier(tier);
            user = userRepository.save(user);
        }
        return new Registered(objectMapper.readTree(response).get("token").asText(), user);
    }

    private StudyPack pack(User owner, StudyPackStatus status) {
        StudyPack pack = new StudyPack();
        pack.setOwner(owner);
        pack.setTitle("Transactions");
        pack.setFileName("transactions.pdf");
        pack.setContentType("application/pdf");
        pack.setExtension("pdf");
        pack.setSizeBytes(1234);
        pack.setStatus(status);
        return studyPackRepository.save(pack);
    }

    /** A READY pack whose chunks are really embedded, with the metadata PackEmbeddingService writes. */
    private StudyPack readyPack(User owner) {
        StudyPack pack = pack(owner, StudyPackStatus.READY);
        List<String> chunks = PackChatFixtures.TRANSACTIONS_CHAPTER;
        List<Document> documents = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("source", "pack");
            metadata.put("packId", String.valueOf(pack.getId()));
            metadata.put("ownerId", String.valueOf(owner.getId()));
            metadata.put("chunkIndex", i);
            metadata.put("page", 3 + i);
            metadata.put("pageEnd", 4 + i);
            metadata.put("section", "Chapter 2 > Transactions");
            metadata.put("elementType", "text");
            String id = PackEmbeddingService.vectorId(pack.getId(), i);
            documents.add(Document.builder().id(id).text(chunks.get(i)).metadata(metadata).build());
            vectorIds.add(id);
        }
        vectorStore.add(documents);
        return pack;
    }

    private RequestBuilder ask(String token, long packId, String message) throws Exception {
        return post("/api/packs/" + packId + "/chat")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM) // what the frontend sends
                .content(objectMapper.writeValueAsString(Map.of("message", message)));
    }

    /** Runs a chat request to completion, including the async re-dispatch, and parses the SSE body. */
    private List<SseEvent> chat(String token, long packId, String message) throws Exception {
        MvcResult started = mockMvc.perform(ask(token, packId, message))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(started)).andExpect(status().isOk());
        assertThat(started.getResponse().getContentType()).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);
        return parseSse(started.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private List<SseEvent> parseSse(String body) throws Exception {
        List<SseEvent> events = new ArrayList<>();
        for (String frame : body.split("\n\n")) {
            if (frame.isBlank()) {
                continue;
            }
            String name = null;
            StringBuilder data = new StringBuilder();
            for (String line : frame.split("\n")) {
                if (line.startsWith("event:")) {
                    name = line.substring("event:".length()).trim();
                } else if (line.startsWith("data:")) {
                    data.append(line.substring("data:".length()));
                }
            }
            events.add(new SseEvent(name, objectMapper.readTree(data.toString())));
        }
        return events;
    }

    private long tokensUsed(User user) {
        LocalDate period = Instant.now().atOffset(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1);
        return llmUsageRepository.findTokensUsed(user.getId(), period).orElse(0L);
    }

    private long statementsFor(RequestBuilder request) throws Exception {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        boolean wasEnabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        try {
            mockMvc.perform(request).andExpect(status().isOk());
            return statistics.getPrepareStatementCount();
        } finally {
            statistics.setStatisticsEnabled(wasEnabled);
        }
    }

    @Test
    void anAnswerStreamsSourcesThenDeltasThenDoneAndIsPersistedAndCharged() throws Exception {
        Registered owner = register(Tier.FREE);
        StudyPack pack = readyPack(owner.user());
        llm.script = p -> Flux.just(
                text("ACID stands for Atomicity, Consistency, "),
                text("Isolation and Durability [1]."),
                text(" Nothing else is claimed [9]."), // [9] isn't a real source: not cited
                usage(900, 150));

        List<SseEvent> events = chat(owner.token(), pack.getId(), "  " + ANSWERABLE + "  ");

        assertThat(events).extracting(SseEvent::name).containsExactly("sources", "delta", "delta", "delta", "done");

        JsonNode sources = events.get(0).data().get("sources");
        assertThat(sources.size()).isBetween(1, 6);
        JsonNode first = sources.get(0);
        assertThat(first.get("n").asInt()).isEqualTo(1);
        assertThat(first.get("page").asInt()).isEqualTo(3); // chunk 0 is the ACID chunk
        assertThat(first.get("pageEnd").asInt()).isEqualTo(4);
        assertThat(first.get("section").asText()).isEqualTo("Chapter 2 > Transactions");
        assertThat(first.get("snippet").asText()).startsWith("ACID properties.").hasSizeLessThanOrEqualTo(301);
        assertThat(first.has("text")).isFalse(); // full chunk text stays server-side

        assertThat(events.get(1).data().get("text").asText()).isEqualTo("ACID stands for Atomicity, Consistency, ");
        assertThat(events.get(2).data().get("text").asText()).isEqualTo("Isolation and Durability [1].");

        JsonNode done = events.get(4).data();
        long messageId = done.get("messageId").asLong();
        assertThat(done.get("citedSources").toString()).isEqualTo("[1]");
        assertThat(done.get("usage").toString())
                .isEqualTo("{\"promptTokens\":900,\"completionTokens\":150,\"totalTokens\":1050}");
        assertThat(done.get("quota").toString()).isEqualTo("{\"used\":1050,\"limit\":20000}");
        assertThat(tokensUsed(owner.user())).isEqualTo(1050);

        // The prompt: system rules, then the trimmed question with the retrieved sources.
        List<Message> sent = llm.prompts.get(0).getInstructions();
        assertThat(sent.get(0).getMessageType()).isEqualTo(MessageType.SYSTEM);
        assertThat(sent.get(sent.size() - 1).getText()).contains("<source n=\"1\"", "Question: " + ANSWERABLE + "\n");

        // History: question then full answer, with the answer's sources and citations.
        mockMvc.perform(get("/api/packs/" + pack.getId() + "/chat").header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages.length()").value(2))
                .andExpect(jsonPath("$.messages[0].role").value("USER"))
                .andExpect(jsonPath("$.messages[0].content").value(ANSWERABLE))
                .andExpect(jsonPath("$.messages[0].sources").isEmpty())
                .andExpect(jsonPath("$.messages[0].citedSources").isEmpty())
                .andExpect(jsonPath("$.messages[1].id").value(messageId))
                .andExpect(jsonPath("$.messages[1].role").value("ASSISTANT"))
                .andExpect(jsonPath("$.messages[1].content").value(
                        "ACID stands for Atomicity, Consistency, Isolation and Durability [1]. Nothing else is claimed [9]."))
                .andExpect(jsonPath("$.messages[1].sources.length()").value(sources.size()))
                .andExpect(jsonPath("$.messages[1].sources[0].snippet").value(first.get("snippet").asText()))
                .andExpect(jsonPath("$.messages[1].citedSources[0]").value(1))
                .andExpect(jsonPath("$.messages[1].createdAt").isNotEmpty())
                .andExpect(jsonPath("$.quota.used").value(1050))
                .andExpect(jsonPath("$.quota.limit").value(20000));
    }

    @Test
    void followUpQuestionsCarryTheRecentTurnsWithoutTheirOldCitationNumbers() throws Exception {
        Registered owner = register(Tier.FREE);
        StudyPack pack = readyPack(owner.user());
        llm.script = p -> Flux.just(text("Atomicity, Consistency, Isolation, Durability [1]."), usage(10, 5));
        chat(owner.token(), pack.getId(), ANSWERABLE);

        chat(owner.token(), pack.getId(), "What does the durability part guarantee after a crash?");

        List<Message> sent = llm.prompts.get(1).getInstructions();
        assertThat(sent).extracting(Message::getMessageType).containsExactly(
                MessageType.SYSTEM, MessageType.USER, MessageType.ASSISTANT, MessageType.USER);
        assertThat(sent.get(1).getText()).isEqualTo(ANSWERABLE);
        assertThat(sent.get(2).getText()).isEqualTo("Atomicity, Consistency, Isolation, Durability.");
    }

    @Test
    void aQuestionTheDocumentDoesNotCoverIsAnsweredWithoutTheLlmAndCostsNothing() throws Exception {
        Registered owner = register(Tier.FREE);
        StudyPack pack = readyPack(owner.user());

        List<SseEvent> events = chat(owner.token(), pack.getId(), OFF_TOPIC);

        assertThat(events).extracting(SseEvent::name).containsExactly("sources", "delta", "done");
        assertThat(events.get(0).data().toString()).isEqualTo("{\"sources\":[]}");
        assertThat(events.get(1).data().get("text").asText()).contains("doesn't seem to cover");
        JsonNode done = events.get(2).data();
        assertThat(done.get("messageId").asLong()).isPositive();
        assertThat(done.get("citedSources").toString()).isEqualTo("[]");
        assertThat(done.get("usage").toString())
                .isEqualTo("{\"promptTokens\":0,\"completionTokens\":0,\"totalTokens\":0}");
        assertThat(done.get("quota").toString()).isEqualTo("{\"used\":0,\"limit\":20000}");

        assertThat(llm.prompts).isEmpty();
        assertThat(tokensUsed(owner.user())).isZero();
        assertThat(messageRepository.findByPackIdOrderByCreatedAtDescIdDesc(pack.getId(),
                org.springframework.data.domain.Limit.of(10))).hasSize(2);
    }

    @Test
    void aProviderRateLimitMidStreamEndsWithAnErrorEventAndPersistsNothing() throws Exception {
        Registered owner = register(Tier.FREE);
        StudyPack pack = readyPack(owner.user());
        llm.script = p -> Flux.concat(Flux.just(text("ACID stands for")), Flux.error(
                WebClientResponseException.create(429, "Too Many Requests", null, null, StandardCharsets.UTF_8)));

        List<SseEvent> events = chat(owner.token(), pack.getId(), ANSWERABLE);

        assertThat(events).extracting(SseEvent::name).containsExactly("sources", "delta", "error");
        JsonNode error = events.get(2).data();
        assertThat(error.get("code").asText()).isEqualTo("LLM_RATE_LIMITED");
        assertThat(error.get("message").asText()).isNotBlank();
        assertThat(messageRepository.findByPackIdOrderByCreatedAtDescIdDesc(pack.getId(),
                org.springframework.data.domain.Limit.of(10))).isEmpty();
        assertThat(tokensUsed(owner.user())).isZero(); // no usage was reported for the failed call
    }

    @Test
    void anyOtherLlmFailureIsAnLlmErrorEvent() throws Exception {
        Registered owner = register(Tier.FREE);
        StudyPack pack = readyPack(owner.user());
        llm.script = p -> Flux.error(new IllegalStateException("connection reset"));

        List<SseEvent> events = chat(owner.token(), pack.getId(), ANSWERABLE);

        assertThat(events).extracting(SseEvent::name).containsExactly("sources", "error");
        assertThat(events.get(1).data().get("code").asText()).isEqualTo(ChatStreamEvent.ErrorEvent.LLM_ERROR);
        assertThat(events.get(1).data().get("message").asText()).doesNotContain("connection reset");
    }

    @Test
    void withoutProviderUsageTheQuotaIsChargedAnEstimate() throws Exception {
        Registered owner = register(Tier.FREE);
        StudyPack pack = readyPack(owner.user());
        llm.script = p -> Flux.just(text("Atomicity, Consistency, Isolation and Durability [1]."));

        List<SseEvent> events = chat(owner.token(), pack.getId(), ANSWERABLE);

        JsonNode usage = events.get(events.size() - 1).data().get("usage");
        assertThat(usage.get("promptTokens").asInt()).isPositive();       // ~ prompt chars / 4
        assertThat(usage.get("completionTokens").asInt()).isEqualTo(14); // 53 chars / 4, rounded up
        assertThat(tokensUsed(owner.user())).isEqualTo(usage.get("totalTokens").asLong());
    }

    @Test
    void aClientThatGoesAwayCancelsTheLlmStreamAndIsStillCharged() throws Exception {
        Registered owner = register(Tier.FREE);
        StudyPack pack = readyPack(owner.user());
        AtomicBoolean upstreamCancelled = new AtomicBoolean();
        llm.script = p -> Flux.concat(Flux.just(text("ACID stands for")), Flux.<ChatResponse>never())
                .doOnCancel(() -> upstreamCancelled.set(true));

        List<ChatStreamEvent> received = new CopyOnWriteArrayList<>();
        Disposable subscription = packChatService.chat(owner.user(), pack.getId(), ANSWERABLE).subscribe(received::add);
        awaitUntil(() -> received.size() == 2); // sources + first delta, then the LLM hangs
        subscription.dispose(); // what Spring MVC does when the SSE client disconnects or times out

        awaitUntil(upstreamCancelled::get);
        awaitUntil(() -> tokensUsed(owner.user()) > 0); // the charge is recorded off-thread
        assertThat(received).extracting(ChatStreamEvent::eventName).containsExactly("sources", "delta");
        assertThat(messageRepository.findByPackIdOrderByCreatedAtDescIdDesc(pack.getId(),
                org.springframework.data.domain.Limit.of(10))).isEmpty();
    }

    @Test
    void refusalsBeforeStreamingAreJsonErrorsEvenForAnEventStreamAccept() throws Exception {
        Registered owner = register(Tier.FREE);
        Registered other = register(Tier.FREE);
        StudyPack ready = pack(owner.user(), StudyPackStatus.READY);
        StudyPack queued = pack(owner.user(), StudyPackStatus.QUEUED);

        mockMvc.perform(ask(owner.token(), queued.getId(), ANSWERABLE))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("PACK_NOT_READY"))
                .andExpect(jsonPath("$.message").isNotEmpty());
        mockMvc.perform(ask(other.token(), ready.getId(), ANSWERABLE))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").isNotEmpty());
        mockMvc.perform(ask(owner.token(), 999_999_999L, ANSWERABLE))
                .andExpect(status().isNotFound());
        mockMvc.perform(ask(owner.token(), ready.getId(), "   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").isNotEmpty());
        mockMvc.perform(ask(owner.token(), ready.getId(), "x".repeat(2001)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("message: must be between 1 and 2000 characters"));
        mockMvc.perform(ask(owner.token(), ready.getId(), "x".repeat(2000) + "   ")) // trimmed first
                .andExpect(request().asyncStarted());

        llmUsageRepository.addTokens(owner.user().getId(),
                Instant.now().atOffset(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1), 20_000);
        mockMvc.perform(ask(owner.token(), ready.getId(), ANSWERABLE))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("CHAT_QUOTA_EXCEEDED"));

        when(serverChatClientProvider.require()).thenThrow(new PackChatException(HttpStatus.SERVICE_UNAVAILABLE,
                PackChatException.CHAT_UNAVAILABLE, "Chat is not available right now."));
        mockMvc.perform(ask(owner.token(), ready.getId(), ANSWERABLE))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("CHAT_UNAVAILABLE"));

        assertThat(llm.prompts).isEmpty();
    }

    @Test
    void historyIsTheLatestFiftyOldestFirstAtAFixedStatementCount() throws Exception {
        Registered owner = register(Tier.FREE);
        StudyPack pack = pack(owner.user(), StudyPackStatus.READY);
        LocalDateTime base = LocalDateTime.of(2026, 9, 1, 12, 0);
        saveMessages(pack.getId(), base, 0, 1);
        long withOne = statementsFor(get("/api/packs/" + pack.getId() + "/chat")
                .header("Authorization", "Bearer " + owner.token()));
        saveMessages(pack.getId(), base, 1, 60);
        long withSixty = statementsFor(get("/api/packs/" + pack.getId() + "/chat")
                .header("Authorization", "Bearer " + owner.token()));

        // JWT filter (revoked-token check + user), controller user lookup, pack ownership check,
        // the messages page, and this month's usage — never one per message.
        assertThat(withSixty).isEqualTo(withOne).isEqualTo(6);

        mockMvc.perform(get("/api/packs/" + pack.getId() + "/chat").header("Authorization", "Bearer " + owner.token()))
                .andExpect(jsonPath("$.messages.length()").value(50))
                .andExpect(jsonPath("$.messages[0].content").value("message 10"))
                .andExpect(jsonPath("$.messages[49].content").value("message 59"));
    }

    private static void awaitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as("condition not met within 10s").isLessThan(deadline);
            Thread.sleep(20);
        }
    }

    private void saveMessages(Long packId, LocalDateTime base, int from, int to) {
        for (int i = from; i < to; i++) {
            PackChatMessage message = new PackChatMessage();
            message.setPackId(packId);
            message.setRole(i % 2 == 0 ? ChatRole.USER : ChatRole.ASSISTANT);
            message.setContent("message " + i);
            message.setCreatedAt(base.plusSeconds(i));
            messageRepository.save(message);
        }
    }

    @Test
    void clearingHistoryIsOwnerOnly() throws Exception {
        Registered owner = register(Tier.FREE);
        Registered other = register(Tier.FREE);
        StudyPack pack = pack(owner.user(), StudyPackStatus.READY);
        saveMessages(pack.getId(), LocalDateTime.now(), 0, 4);

        mockMvc.perform(delete("/api/packs/" + pack.getId() + "/chat").header("Authorization", "Bearer " + other.token()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/packs/" + pack.getId() + "/chat").header("Authorization", "Bearer " + other.token()))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/packs/" + pack.getId() + "/chat").header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/packs/" + pack.getId() + "/chat").header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages").isEmpty());
    }

    @Test
    void deletingThePackDeletesItsHistory() throws Exception {
        Registered owner = register(Tier.FREE);
        StudyPack pack = pack(owner.user(), StudyPackStatus.READY);
        StudyPack kept = pack(owner.user(), StudyPackStatus.READY);
        saveMessages(pack.getId(), LocalDateTime.now(), 0, 4);
        saveMessages(kept.getId(), LocalDateTime.now(), 0, 2);

        mockMvc.perform(delete("/api/packs/" + pack.getId()).header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isNoContent());

        assertThat(studyPackRepository.findById(pack.getId())).isEmpty();
        assertThat(messageRepository.findAll()).noneMatch(m -> m.getPackId().equals(pack.getId()));
        assertThat(messageRepository.findAll()).filteredOn(m -> m.getPackId().equals(kept.getId())).hasSize(2);
    }

    @Test
    void limitsCarryTheTierChatBudgetAndThisMonthsUsage() throws Exception {
        Registered free = register(Tier.FREE);
        llmUsageRepository.addTokens(free.user().getId(),
                Instant.now().atOffset(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1), 1234);
        // Last month's usage doesn't count towards this month.
        llmUsageRepository.addTokens(free.user().getId(),
                Instant.now().atOffset(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1).minusMonths(1), 99_999);
        mockMvc.perform(get("/api/packs/limits").header("Authorization", "Bearer " + free.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chatTokensPerMonth").value(20000))
                .andExpect(jsonPath("$.chatTokensUsed").value(1234));

        Registered pro = register(Tier.PRO);
        mockMvc.perform(get("/api/packs/limits").header("Authorization", "Bearer " + pro.token()))
                .andExpect(jsonPath("$.chatTokensPerMonth").value(500000))
                .andExpect(jsonPath("$.chatTokensUsed").value(0));
        Registered max = register(Tier.MAX);
        mockMvc.perform(get("/api/packs/limits").header("Authorization", "Bearer " + max.token()))
                .andExpect(jsonPath("$.chatTokensPerMonth").value(2000000));
    }

    @Test
    void everyChatRouteRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/packs/1/chat")).andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/packs/1/chat")).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/packs/1/chat").contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"hi\"}"))
                .andExpect(status().isForbidden());
    }
}
