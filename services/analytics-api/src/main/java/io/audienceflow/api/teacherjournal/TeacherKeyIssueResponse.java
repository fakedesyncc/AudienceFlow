package io.audienceflow.api.teacherjournal;

public record TeacherKeyIssueResponse(
        int teacherId,
        String teacherName,
        String accessKey
) {
}
