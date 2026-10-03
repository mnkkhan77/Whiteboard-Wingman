package com.mockinterview.backend.service;

import com.mockinterview.backend.entity.CourseLesson;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** How a validated outline's lessons are trimmed to the pack's chunk count and assigned ranges. */
class PackCourseGeneratorPlanTest {

    private static List<CourseLesson> lessons(int n) {
        List<CourseLesson> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            CourseLesson lesson = new CourseLesson();
            lesson.setTitle("Lesson " + i);
            list.add(lesson);
        }
        return list;
    }

    @Test
    void rangesAreContiguousEvenlySizedAndCoverTheWholePack() {
        List<CourseLesson> kept = PackCourseGenerator.assignChunkRanges(lessons(4), 10);

        assertThat(kept).hasSize(4);
        assertThat(kept.get(0).getSourceChunkStart()).isZero();
        // every lesson's end is the next lesson's start, i.e. no gaps and no overlaps
        for (int i = 0; i < kept.size() - 1; i++) {
            assertThat(kept.get(i).getSourceChunkEnd()).isEqualTo(kept.get(i + 1).getSourceChunkStart());
        }
        assertThat(kept.get(kept.size() - 1).getSourceChunkEnd()).isEqualTo(10);
    }

    @Test
    void moreLessonsThanChunksAreTrimmedFromTheEndSoEveryKeptLessonGetsAtLeastOneChunk() {
        List<CourseLesson> kept = PackCourseGenerator.assignChunkRanges(lessons(8), 3);

        assertThat(kept).extracting(CourseLesson::getTitle).containsExactly("Lesson 0", "Lesson 1", "Lesson 2");
        assertThat(kept).allSatisfy(l -> assertThat(l.getSourceChunkEnd()).isGreaterThan(l.getSourceChunkStart()));
        assertThat(kept.get(0).getSourceChunkStart()).isZero();
        assertThat(kept.get(2).getSourceChunkEnd()).isEqualTo(3);
    }

    @Test
    void fewerLessonsThanChunksKeepsEveryLesson() {
        List<CourseLesson> kept = PackCourseGenerator.assignChunkRanges(lessons(3), 100);

        assertThat(kept).hasSize(3);
        assertThat(kept.get(2).getSourceChunkEnd()).isEqualTo(100);
    }

    @Test
    void zeroChunksKeepsNoLessons() {
        assertThat(PackCourseGenerator.assignChunkRanges(lessons(5), 0)).isEmpty();
    }

    @Test
    void manyLessonsStillProduceStrictlyIncreasingRanges() {
        List<CourseLesson> kept = PackCourseGenerator.assignChunkRanges(lessons(20), 37);

        assertThat(kept).hasSize(20);
        assertThat(IntStream.range(0, kept.size()))
                .allSatisfy(i -> assertThat(kept.get(i).getSourceChunkStart()).isLessThan(kept.get(i).getSourceChunkEnd()));
    }
}
