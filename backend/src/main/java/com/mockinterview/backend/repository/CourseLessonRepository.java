package com.mockinterview.backend.repository;

import com.mockinterview.backend.entity.CourseLesson;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CourseLessonRepository extends JpaRepository<CourseLesson, Long> {

    List<CourseLesson> findByPackIdOrderByModuleIndexAscLessonIndexInModuleAsc(Long packId);

    Optional<CourseLesson> findByIdAndPackId(Long id, Long packId);

    /** Bulk delete for replace-on-regenerate — a derived deleteBy would load every row first.
     *  Only called inside PackCourseBankWriter's transaction. */
    @Modifying
    @Query("delete from CourseLesson c where c.packId = :packId")
    int deleteByPackId(@Param("packId") Long packId);
}
