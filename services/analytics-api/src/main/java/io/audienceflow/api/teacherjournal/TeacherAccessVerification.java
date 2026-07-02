package io.audienceflow.api.teacherjournal;

public record TeacherAccessVerification(
        boolean verified,
        int teacherId,
        String teacherName
) {
    public static TeacherAccessVerification rejected(int teacherId) {
        return new TeacherAccessVerification(false, teacherId, "");
    }
}
