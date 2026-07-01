package io.audienceflow.api.auth;

import io.audienceflow.api.security.JwtService;
import io.audienceflow.api.users.UserAccount;
import io.audienceflow.api.users.UserRepository;
import io.audienceflow.api.users.UserView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.security.Principal;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/auth")
public class AuthController {
    // Constant dummy BCrypt hash (cost 10) used to keep the not-found/inactive path performing
    // exactly one BCrypt verification, so response timing does not reveal whether an email exists.
    private static final String DUMMY_PASSWORD_HASH =
            "$2a$10$ET84MtYSeG4lfN8XyEDOueAgppXyF4td7CFYTEopN2F2PbzNUYMbC";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final LoginRateLimiter loginRateLimiter;

    public AuthController(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            LoginRateLimiter loginRateLimiter
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.loginRateLimiter = loginRateLimiter;
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        String clientIp = clientIp(httpRequest);
        if (loginRateLimiter.isLocked(request.email(), clientIp)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many login attempts");
        }

        Optional<UserAccount> candidate = userRepository.findByEmail(request.email())
                .filter(UserAccount::active);
        boolean matches;
        if (candidate.isPresent()) {
            matches = passwordEncoder.matches(request.password(), candidate.get().passwordHash());
        } else {
            // Perform a throwaway verification so every login does exactly one BCrypt match,
            // eliminating the timing signal that distinguishes valid from invalid emails.
            passwordEncoder.matches(request.password(), DUMMY_PASSWORD_HASH);
            matches = false;
        }

        if (!matches) {
            loginRateLimiter.recordFailure(request.email(), clientIp);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }

        loginRateLimiter.recordSuccess(request.email(), clientIp);
        UserAccount user = candidate.get();
        return new LoginResponse(jwtService.issue(user), UserView.from(user), jwtService.ttlMinutes());
    }

    @GetMapping("/me")
    public UserView me(Principal principal) {
        var user = userRepository.findByEmail(principal.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User is not active"));
        return UserView.from(user);
    }

    private static String clientIp(HttpServletRequest request) {
        if (request == null) {
            return "";
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded)) {
            return forwarded.split(",", 2)[0].trim();
        }
        String remote = request.getRemoteAddr();
        return remote == null ? "" : remote;
    }

    public record LoginRequest(
            @Email @NotBlank String email,
            @NotBlank String password
    ) {
    }

    public record LoginResponse(
            String token,
            UserView user,
            long expiresInMinutes
    ) {
    }
}
