package io.audienceflow.api.cameras;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/cameras")
public class CameraController {
    private final CameraRepository cameraRepository;

    public CameraController(CameraRepository cameraRepository) {
        this.cameraRepository = cameraRepository;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('TEACHER', 'TECHNICIAN', 'ADMIN')")
    public List<Camera> cameras(Authentication authentication) {
        boolean canSeeSource = authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_TECHNICIAN")
                        || authority.getAuthority().equals("ROLE_ADMIN"));
        return cameraRepository.findAll().stream()
                .map(camera -> canSeeSource ? camera : camera.masked())
                .toList();
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('TECHNICIAN', 'ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public Camera create(@Valid @RequestBody CameraRequest request) {
        return cameraRepository.create(request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('TECHNICIAN', 'ADMIN')")
    public Camera update(@PathVariable int id, @Valid @RequestBody CameraRequest request) {
        return cameraRepository.update(id, request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable int id) {
        cameraRepository.delete(id);
    }
}
