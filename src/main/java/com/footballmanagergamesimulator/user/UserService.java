package com.footballmanagergamesimulator.user;

import com.footballmanagergamesimulator.person.PersonProfileService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final PersonProfileService personProfileService;

    public UserService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       PersonProfileService personProfileService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.personProfileService = personProfileService;
    }

    public User getCurrentAuthenticatedUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }
        return userRepository.findByUsernameIgnoreCase(authentication.getName()).orElse(null);
    }

    @Transactional
    public User register(RegisterRequest request) {
        String username = request.username().trim().toLowerCase(Locale.ROOT);
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        if (userRepository.existsByUsernameIgnoreCase(username)) {
            throw new IllegalArgumentException("Username already taken");
        }
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new IllegalArgumentException("Email already registered");
        }

        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setEmail(email);
        user.setFirstName(request.displayName().trim());
        user.setLastName("");
        user.setCareerRole(request.careerRole());
        user.setRoles("USER");
        user.setActive(true);

        if (request.careerRole() == CareerRole.CHAIRMAN) {
            user.setTeamId(null);
            user.setLastTeamId(null);
            user.setManagerId(null);
            user.setFired(false);
            user.setEverManaged(false);
        }

        try {
            User saved = userRepository.saveAndFlush(user);
            personProfileService.createForUser(saved, request.displayName());
            return saved;
        } catch (DataIntegrityViolationException exception) {
            throw new IllegalArgumentException("Username or email already registered", exception);
        }
    }

    /** Compatibility for the server-rendered form; uses the same secure registration path. */
    public void saveUser(UserDto dto) {
        String displayName = ((dto.getFirstName() == null ? "" : dto.getFirstName()) + " "
                + (dto.getLastName() == null ? "" : dto.getLastName())).trim();
        register(new RegisterRequest(dto.getUsername(), dto.getEmail(), dto.getPassword(),
                displayName, CareerRole.MANAGER));
    }
}
