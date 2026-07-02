package io.audienceflow.api.teacherjournal;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/teacher-journal")
public class TeacherJournalController {
    private final TeacherJournalRepository repository;

    public TeacherJournalController(TeacherJournalRepository repository) {
        this.repository = repository;
    }

    @PostMapping("/verify")
    @PreAuthorize("hasAnyRole('TEACHER', 'TECHNICIAN', 'ADMIN')")
    public TeacherAccessVerification verify(@Valid @RequestBody TeacherAccessVerifyRequest request) {
        return repository.verify(request.teacherId(), request.accessKey())
                .orElseGet(() -> TeacherAccessVerification.rejected(request.teacherId()));
    }

    @PostMapping("/keys")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public TeacherKeyIssueResponse issueKey(@Valid @RequestBody CreateTeacherKeyRequest request) {
        try {
            return repository.issueKey(request.teacherId(), request.label());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
        }
    }
}
