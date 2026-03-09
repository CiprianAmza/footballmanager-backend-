package com.footballmanagergamesimulator.user;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "${cors.allowed-origins:http://localhost:4200}")
public class AuthController {

    @Autowired
    private UserRepository userRepository;

    /**
     * Simple login: find user by username, no password check for now.
     * Creates the user if it doesn't exist.
     */
    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        Map<String, Object> result = new LinkedHashMap<>();

        if (username == null || username.isBlank()) {
            result.put("success", false);
            result.put("error", "Username is required");
            return result;
        }

        username = username.trim();

        // Find or create user
        Optional<User> existing = userRepository.findByUsername(username);
        User user;
        if (existing.isPresent()) {
            user = existing.get();
        } else {
            user = new User();
            user.setUsername(username);
            user.setActive(true);
            user.setRoles("USER");
            userRepository.save(user);
        }

        result.put("success", true);
        result.put("userId", user.getId());
        result.put("username", user.getUsername());
        result.put("teamId", user.getTeamId());
        result.put("managerId", user.getManagerId());
        return result;
    }

    /**
     * Verify session / restore state on page refresh.
     */
    @GetMapping("/me")
    public Map<String, Object> me(@RequestParam int userId) {
        Map<String, Object> result = new LinkedHashMap<>();
        Optional<User> userOpt = userRepository.findById(userId);
        if (userOpt.isEmpty()) {
            result.put("success", false);
            result.put("error", "User not found");
            return result;
        }

        User user = userOpt.get();
        result.put("success", true);
        result.put("userId", user.getId());
        result.put("username", user.getUsername());
        result.put("teamId", user.getTeamId());
        result.put("managerId", user.getManagerId());
        return result;
    }
}
