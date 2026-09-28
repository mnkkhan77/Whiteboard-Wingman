package com.mockinterview.backend.repository;

import com.mockinterview.backend.entity.PackQuizQuestion;
import com.mockinterview.backend.entity.QuestionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PackQuizQuestionRepository extends JpaRepository<PackQuizQuestion, Long> {

    /** How many questions of one type a pack's bank has. */
    record TypeCount(QuestionType type, Long count) {
    }

    /** One grouped query for both section sizes of a pack quiz. */
    @Query("""
            select new com.mockinterview.backend.repository.PackQuizQuestionRepository$TypeCount(q.questionType, count(q))
            from PackQuizQuestion q where q.packId = :packId group by q.questionType
            """)
    List<TypeCount> countByType(@Param("packId") Long packId);

    /** A bank holds ~12 questions per type, so picking the closest-difficulty unused one in
     *  memory is cheaper than encoding "closest difficulty" as SQL over string enums. */
    List<PackQuizQuestion> findByPackIdAndQuestionTypeOrderByIdAsc(Long packId, QuestionType questionType);

    List<PackQuizQuestion> findByPackIdOrderByIdAsc(Long packId);

    /** Bulk delete for replace-on-regenerate — a derived deleteBy would load every row first.
     *  Only called inside PackQuizBankWriter's transaction. */
    @Modifying
    @Query("delete from PackQuizQuestion q where q.packId = :packId")
    int deleteByPackId(@Param("packId") Long packId);
}
