package com.footballmanagergamesimulator.user;

import com.footballmanagergamesimulator.person.PersonProfile;
import com.footballmanagergamesimulator.person.PersonProfileService;
import com.footballmanagergamesimulator.economy.RegentEconomyProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final UserService userService;
    private final CurrentUserService currentUserService;
    private final PersonProfileService personProfileService;
    private final RegentEconomyProperties regentProperties;
    private final HttpSessionSecurityContextRepository securityContextRepository =
            new HttpSessionSecurityContextRepository();

    public AuthController(AuthenticationManager authenticationManager,
                          UserService userService,
                          CurrentUserService currentUserService,
                          PersonProfileService personProfileService,
                          RegentEconomyProperties regentProperties) {
        this.authenticationManager = authenticationManager;
        this.userService = userService;
        this.currentUserService = currentUserService;
        this.personProfileService = personProfileService;
        this.regentProperties = regentProperties;
    }

    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        try {
            User user = userService.register(request);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(AuthResponse.success(user, personProfileService.requireForUser(user),
                            regentProperties.isEnabled()));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(AuthResponse.failure(exception.getMessage()));
        }
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request,
                                              HttpServletRequest servletRequest,
                                              HttpServletResponse servletResponse) {
        try {
            Authentication authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(request.username(), request.password()));
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            if (servletRequest.getSession(false) != null) {
                servletRequest.getSession(false).invalidate();
            }
            servletRequest.getSession(true);
            securityContextRepository.saveContext(context, servletRequest, servletResponse);

            User user = currentUserService.requireUser();
            PersonProfile profile = personProfileService.requireForUser(user);
            return ResponseEntity.ok(AuthResponse.success(user, profile, regentProperties.isEnabled()));
        } catch (AuthenticationException exception) {
            SecurityContextHolder.clearContext();
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(AuthResponse.failure("Invalid username or password"));
        }
    }

    @GetMapping("/me")
    public ResponseEntity<AuthResponse> me() {
        User user = currentUserService.getUserOrNull();
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(AuthResponse.failure("Not authenticated"));
        }
        return ResponseEntity.ok(AuthResponse.success(user, personProfileService.requireForUser(user),
                regentProperties.isEnabled()));
    }
}
