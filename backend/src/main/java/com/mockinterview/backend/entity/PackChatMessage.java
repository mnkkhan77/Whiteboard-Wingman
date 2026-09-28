package com.mockinterview.backend.entity;

import com.mockinterview.backend.dto.ChatSourceDto;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * One message of a pack's chat history. packId is a plain column rather than a @ManyToOne: the
 * history is always loaded for an already-authorized pack id, so an association would only add a
 * join (or a lazy proxy) nobody reads. Deleting the pack cascades in the database (V18).
 */
@Entity
@Table(name = "pack_chat_messages")
@Getter
@Setter
public class PackChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pack_id", nullable = false)
    private Long packId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ChatRole role;

    // TEXT, not @Lob — see the note on Answer.answerText.
    @Column(nullable = false, columnDefinition = "text")
    private String content;

    /** The numbered sources the answer was grounded in (empty for USER messages). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<ChatSourceDto> sources = new ArrayList<>();

    /** The [n] markers actually present in the answer (empty for USER messages). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<Integer> citedSources = new ArrayList<>();

    /** Provider-reported (or, failing that, estimated) usage of the answer; null on USER messages. */
    private Integer promptTokens;

    private Integer completionTokens;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }
}
