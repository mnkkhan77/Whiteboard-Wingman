import { Link } from "react-router-dom";
import type { CourseDto } from "../types/api";

interface CourseModuleListProps {
  packId: number;
  course: CourseDto;
}

/** Every module and lesson of a READY outline, with progress and a link into each lesson. */
export function CourseModuleList({ packId, course }: CourseModuleListProps) {
  return (
    <section className="card">
      <div className="session-row-title">
        <h2>Course</h2>
        <span className="hint">
          {course.completedLessons} of {course.totalLessons} lessons completed
        </span>
      </div>
      {course.modules.map((module, index) => (
        <div key={module.title} className="course-module">
          <h3 className="course-module-title">
            Module {index + 1}: {module.title}
          </h3>
          <ul className="course-lesson-list">
            {module.lessons.map((lesson) => (
              <li key={lesson.id}>
                <Link to={`/packs/${packId}/course/lessons/${lesson.id}`} className="course-lesson-link">
                  <span className={`course-lesson-check ${lesson.completed ? "course-lesson-check-done" : ""}`} aria-hidden>
                    {lesson.completed ? "✓" : ""}
                  </span>
                  <span className="course-lesson-text">
                    <strong>{lesson.title}</strong>
                    <span className="hint">{lesson.summary}</span>
                  </span>
                </Link>
              </li>
            ))}
          </ul>
        </div>
      ))}
    </section>
  );
}
