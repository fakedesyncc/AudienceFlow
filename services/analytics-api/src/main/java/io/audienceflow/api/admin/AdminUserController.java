package io.audienceflow.api.admin;

import io.audienceflow.api.security.PasswordPolicy;
import io.audienceflow.api.users.Role;
import io.audienceflow.api.users.UserRepository;
import io.audienceflow.api.users.UserView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/admin/users")
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserController {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AdminUserController(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @GetMapping
    public List<UserView> users() {
        return userRepository.findAll().stream()
                .map(UserView::from)
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserView create(@Valid @RequestBody CreateUserRequest request) {
        validatePassword(request.email(), request.password());
        if (userRepository.findByEmail(request.email()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "User already exists");
        }
        var user = userRepository.create(
                request.email(),
                request.displayName(),
                request.role(),
                passwordEncoder.encode(request.password())
        );
        return UserView.from(user);
    }

    private static void validatePassword(String email, String password) {
        try {
            PasswordPolicy.requireStrongUserPassword(email, password);
        } catch (IllegalArgumentException exc) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exc.getMessage(), exc);
        }
    }

    public record CreateUserRequest(
            @Email @NotBlank String email,
            @NotBlank @Size(max = 160) String displayName,
            @NotNull Role role,
            @NotBlank @Size(min = PasswordPolicy.MIN_LENGTH, max = PasswordPolicy.MAX_LENGTH) String password
    ) {
    }
}
