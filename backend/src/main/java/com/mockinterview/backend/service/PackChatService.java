package com.mockinterview.backend.service;

import com.mockinterview.backend.config.ChatProperties;
import com.mockinterview.backend.dto.ChatHistoryDto;
import com.mockinterview.backend.dto.ChatMessageDto;
import com.mockinterview.backend.dto.ChatQuotaDto;
import com.mockinterview.backend.dto.ChatSourceDto;
import com.mockinterview.backend.dto.ChatStreamEvent;
import com.mockinterview.backend.dto.ChatStreamEvent.DeltaEvent;
import com.mockinterview.backend.dto.ChatStreamEvent.DoneEvent;
import com.mockinterview.backend.dto.ChatStreamEvent.ErrorEvent;
import com.mockinterview.backend.dto.ChatStreamEvent.SourcesEvent;
import com.mockinterview.backend.dto.ChatUsageDto;
import com.mockinterview.backend.entity.ChatRole;
import com.mockinterview.backend.entity.PackChatMessage;
import com.mockinterview.backend.entity.StudyPack;
import com.mockinterview.backend.entity.StudyPackStatus;
import com.mockinterview.backend.entity.User;
import com.mockinterview.backend.exception.PackChatException;
import com.mockinterview.backend.repository.StudyPackRepository;
import com.mockinterview.backend.service.PackRetrievalService.RetrievedChunk;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * "Chat with a pack" (docs/study-packs-contract.md "Chat with a pack"): orchestrates the refusal
 * checks, retrieval (PackRetrievalService), prompt (ChatPromptBuilder), the streamed LLM answer,
 * quota (ChatQuotaService) and history (PackChatHistoryService). It emits transport-neutral
 * {@link ChatStreamEvent}s; the controller maps them to SSE.
 *
 * Every refusal (503 / 400 / 404 / 409 / 429) is thrown synchronously from {@link #chat}, before
 * any stream exists, so it becomes a normal JSON error. Nothing here is @Transactional: each DB
 * step is its own short transaction, and none is held open while the answer streams.
 */
@Service
@RequiredArgsConstructor
public class PackChatService {

    private static final Logger log = LoggerFactory.getLogger(PackChatService.class);

    static final int HISTORY_PAGE_SIZE = 50;
    static final String NO_CONTEXT_ANSWER = "The document doesn't seem to cover this — I couldn't find a "
            + "passage related to your question. Try rephrasing it, or ask about something the document discusses.";

    /** Rough chars-per-token for English text, only used when the provider reports no usage. */
    private static final int CHARS_PER_TOKEN = 4;
    private static final AtomicBoolean ESTIMATE_LOGGED = new AtomicBoolean();

    /**
     * Where the end-of-stream DB work runs: never on the HTTP client's event-loop thread that
     * delivers the LLM chunks (blocking JDBC there would stall other streams).
     */
    private static final Executor COMPLETION_EXECUTOR = task -> Schedulers.boundedElastic().schedule(task);

    private final StudyPackRepository studyPackRepository;
    private final ServerChatClientProvider serverChatClientProvider;
    private final ChatQuotaService chatQuotaService;
    private final PackRetrievalService packRetrievalService;
    private final ChatPromptBuilder chatPromptBuilder;
    private final PackChatHistoryService packChatHistoryService;
    private final ChatProperties chatProperties;

    public ChatHistoryDto history(User user, Long packId) {
        requireOwned(user, packId);
        List<ChatMessageDto> messages = packChatHistoryService.recent(packId, HISTORY_PAGE_SIZE).stream()
                .map(ChatMessageDto::from).toList();
        return new ChatHistoryDto(messages, chatQuotaService.quota(user));
    }

    public void clearHistory(User user, Long packId) {
        requireOwned(user, packId);
        packChatHistoryService.clear(packId);
    }

    public Flux<ChatStreamEvent> chat(User user, Long packId, String message) {
        ChatClient chatClient = serverChatClientProvider.require(); // 503 before any other work
        String question = validate(message);
        StudyPack pack = studyPackRepository.findByIdAndOwner(packId, user)
                .orElseThrow(() -> new NoSuchElementException("Study pack not found"));
        if (pack.getStatus() != StudyPackStatus.READY) {
            throw new PackChatException(HttpStatus.CONFLICT, PackChatException.PACK_NOT_READY,
                    "This study pack is " + pack.getStatus() + " — chat is available once it's READY.");
        }
        ChatQuotaDto quota = chatQuotaService.requireAvailable(user);
        LocalDateTime askedAt = LocalDateTime.now();

        List<RetrievedChunk> chunks = packRetrievalService.retrieve(packId, user.getId(), question);
        if (chunks.isEmpty()) {
            // Nothing relevant enough: answer without calling the LLM, so it costs no quota.
            PackChatMessage saved = packChatHistoryService.saveExchange(packId, question, askedAt,
                    NO_CONTEXT_ANSWER, List.of(), List.of(), ChatUsageDto.ZERO);
            return Flux.just(new SourcesEvent(List.of()), new DeltaEvent(NO_CONTEXT_ANSWER),
                    new DoneEvent(saved.getId(), List.of(), ChatUsageDto.ZERO, quota));
        }

        List<PackChatMessage> turns = packChatHistoryService.recent(packId, chatProperties.historyTurns() * 2);
        String previousQuestion = lastUserQuestion(turns);
        if (previousQuestion != null) {
            chunks = packRetrievalService.expandForFollowUp(packId, user.getId(), chunks, previousQuestion, question);
        }
        List<ChatSourceDto> sources = chunks.stream().map(RetrievedChunk::toSource).toList();
        Prompt prompt = chatPromptBuilder.build(chunks, turns, question);
        return new AnswerStream(user, packId, question, askedAt, sources, promptChars(prompt))
                .events(chatClient.prompt(prompt).stream().chatResponse());
    }

    private String validate(String message) {
        String trimmed = message == null ? "" : message.trim();
        if (trimmed.isEmpty() || trimmed.length() > chatProperties.maxMessageChars()) {
            throw new IllegalArgumentException(
                    "message: must be between 1 and " + chatProperties.maxMessageChars() + " characters");
        }
        return trimmed;
    }

    private static String lastUserQuestion(List<PackChatMessage> oldestFirst) {
        for (int i = oldestFirst.size() - 1; i >= 0; i--) {
            if (oldestFirst.get(i).getRole() == ChatRole.USER) {
                return oldestFirst.get(i).getContent();
            }
        }
        return null;
    }

    private void requireOwned(User user, Long packId) {
        // Same 404 for "doesn't exist" and "not yours", as in StudyPackService.
        if (!studyPackRepository.existsByIdAndOwner(packId, user)) {
            throw new NoSuchElementException("Study pack not found");
        }
    }

    /**
     * One answer being streamed. Exactly one of complete / error / cancel "settles" it, so the
     * tokens are charged exactly once whichever way the stream ends:
     * - complete: charge the usage, persist question + full answer, emit done;
     * - LLM error: emit error, persist nothing (no partial answers in history), charge only usage
     *   the provider actually reported (a rejected/rate-limited call reports none and costs none);
     * - client gone (disconnect or MVC async timeout cancels the subscription): stop reading the
     *   LLM stream — the cancel propagates to the HTTP call — and charge what was spent so far.
     *   The usage chunk only arrives at the very end, so that is normally the estimate: charging
     *   nothing would let a client dodge the quota by disconnecting just before "done".
     */
    private final class AnswerStream {

        private final User user;
        private final Long packId;
        private final String question;
        private final LocalDateTime askedAt;
        private final List<ChatSourceDto> sources;
        private final int promptChars;
        // Appended on the LLM thread, read by whichever thread settles the answer.
        private final StringBuffer answer = new StringBuffer();
        private final AtomicBoolean settled = new AtomicBoolean();
        private volatile ChatUsageDto reportedUsage;

        AnswerStream(User user, Long packId, String question, LocalDateTime askedAt,
                     List<ChatSourceDto> sources, int promptChars) {
            this.user = user;
            this.packId = packId;
            this.question = question;
            this.askedAt = askedAt;
            this.sources = sources;
            this.promptChars = promptChars;
        }

        Flux<ChatStreamEvent> events(Flux<ChatResponse> responses) {
            Flux<ChatStreamEvent> deltas = responses
                    .doOnNext(this::captureUsage)
                    .map(PackChatService::textOf)
                    .filter(text -> !text.isEmpty())
                    .doOnNext(answer::append)
                    .<ChatStreamEvent>map(DeltaEvent::new)
                    .doOnCancel(() -> COMPLETION_EXECUTOR.execute(this::onCancel));
            return Flux.concat(Mono.just(new SourcesEvent(sources)), deltas, offloaded(this::onComplete))
                    .onErrorResume(error -> offloaded(() -> onError(error)));
        }

        private void captureUsage(ChatResponse response) {
            Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
            if (usage == null || usage.getTotalTokens() == null || usage.getTotalTokens() <= 0) {
                return; // every chunk but the last carries an empty usage
            }
            ChatUsageDto current = reportedUsage;
            if (current == null || usage.getTotalTokens() > current.totalTokens()) {
                reportedUsage = new ChatUsageDto(orZero(usage.getPromptTokens()), orZero(usage.getCompletionTokens()),
                        usage.getTotalTokens());
            }
        }

        private ChatStreamEvent onComplete() {
            if (!settled.compareAndSet(false, true)) {
                throw new IllegalStateException("Answer already settled");
            }
            ChatUsageDto usage = usageOrEstimate();
            ChatQuotaDto quota = chatQuotaService.record(user, usage.totalTokens());
            String text = answer.toString().strip();
            if (text.isEmpty()) {
                // e.g. the whole token budget went to reasoning: tokens were spent, nothing to keep.
                log.warn("Pack chat answer for pack {} was empty ({} tokens charged)", packId, usage.totalTokens());
                return new ErrorEvent(ErrorEvent.LLM_ERROR, "The model returned an empty answer. Please try again.");
            }
            List<Integer> cited = CitationParser.cited(text, sources.size());
            PackChatMessage saved = packChatHistoryService.saveExchange(packId, question, askedAt, text, sources,
                    cited, usage);
            return new DoneEvent(saved.getId(), cited, usage, quota);
        }

        private ChatStreamEvent onError(Throwable error) {
            if (settled.compareAndSet(false, true) && reportedUsage != null) {
                chargeQuietly(reportedUsage.totalTokens());
            }
            boolean rateLimited = isRateLimited(error);
            // Exception text only — never the question/answer content.
            log.warn("Pack chat answer for pack {} failed{}: {}", packId, rateLimited ? " (rate limited)" : "",
                    error.toString());
            return rateLimited
                    ? new ErrorEvent(ErrorEvent.LLM_RATE_LIMITED,
                            "The AI provider is busy right now (rate limited). Please try again in a moment.")
                    : new ErrorEvent(ErrorEvent.LLM_ERROR, "The answer couldn't be completed. Please try again.");
        }

        private void onCancel() {
            if (!settled.compareAndSet(false, true)) {
                return;
            }
            ChatUsageDto usage = usageOrEstimate();
            chargeQuietly(usage.totalTokens());
            log.info("Pack chat stream for pack {} cancelled by the client; charged {} tokens", packId,
                    usage.totalTokens());
        }

        private ChatUsageDto usageOrEstimate() {
            if (reportedUsage != null) {
                return reportedUsage;
            }
            // Fallback when the provider sent no usage chunk (stream_options.include_usage not
            // honoured): ~4 chars per token of prompt and answer. Logged once per JVM, not per answer.
            if (ESTIMATE_LOGGED.compareAndSet(false, true)) {
                log.warn("LLM stream reported no token usage; charging pack chat quota with a ~{} chars/token estimate",
                        CHARS_PER_TOKEN);
            }
            return ChatUsageDto.of(estimateTokens(promptChars), estimateTokens(answer.length()));
        }

        private void chargeQuietly(long tokens) {
            try {
                chatQuotaService.record(user, tokens);
            } catch (RuntimeException e) {
                log.error("Could not record {} pack chat tokens for user {}", tokens, user.getId(), e);
            }
        }
    }

    /**
     * Runs blocking completion work off the stream's thread. suppressCancel: once started it must
     * finish — a client that disconnects right after the last delta must not interrupt the
     * persist/charge half-way.
     */
    private static Mono<ChatStreamEvent> offloaded(Supplier<ChatStreamEvent> work) {
        return Mono.fromFuture(() -> CompletableFuture.supplyAsync(work, COMPLETION_EXECUTOR), true);
    }

    private static String textOf(ChatResponse response) {
        if (response.getResult() == null || response.getResult().getOutput() == null) {
            return ""; // e.g. the final usage-only chunk, which has no choices
        }
        String text = response.getResult().getOutput().getText();
        return text == null ? "" : text;
    }

    static boolean isRateLimited(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof WebClientResponseException e && e.getStatusCode().value() == 429) {
                return true;
            }
            if (t instanceof RestClientResponseException e && e.getStatusCode().value() == 429) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    private static int promptChars(Prompt prompt) {
        int chars = 0;
        for (Message message : prompt.getInstructions()) {
            chars += message.getText() == null ? 0 : message.getText().length();
        }
        return chars;
    }

    private static int estimateTokens(int chars) {
        return (chars + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN;
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }
}
