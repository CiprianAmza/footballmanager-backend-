package com.footballmanagergamesimulator.user;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class UserUiPreferenceService {

    private static final Pattern VALID_KEY = Pattern.compile("[A-Za-z0-9._-]{1,100}");
    private static final int MAX_VALUE_LENGTH = 30_000;

    private final UserUiPreferenceRepository repository;
    private final ObjectMapper objectMapper;

    public UserUiPreferenceService(UserUiPreferenceRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public Optional<PreferenceView> get(int userId, String key) {
        validateKey(key);
        return repository.findByUserIdAndPreferenceKey(userId, key)
                .map(preference -> new PreferenceView(preference.getPreferenceKey(),
                        readValue(preference.getPreferenceValue())));
    }

    @Transactional
    public PreferenceView save(int userId, String key, JsonNode value) {
        validateKey(key);
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException("Preference value must be a JSON object");
        }
        String serialized = writeValue(value);
        if (serialized.length() > MAX_VALUE_LENGTH) {
            throw new IllegalArgumentException("Preference value is too large");
        }
        UserUiPreference preference = repository.findByUserIdAndPreferenceKey(userId, key)
                .orElseGet(UserUiPreference::new);
        preference.setUserId(userId);
        preference.setPreferenceKey(key);
        preference.setPreferenceValue(serialized);
        repository.save(preference);
        return new PreferenceView(key, value);
    }

    @Transactional
    public void delete(int userId, String key) {
        validateKey(key);
        repository.deleteByUserIdAndPreferenceKey(userId, key);
    }

    private void validateKey(String key) {
        if (key == null || !VALID_KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Invalid preference key");
        }
    }

    private JsonNode readValue(String serialized) {
        try {
            return objectMapper.readTree(serialized);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored preference is invalid", exception);
        }
    }

    private String writeValue(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Preference value cannot be serialized", exception);
        }
    }

    public record PreferenceView(String key, JsonNode value) {}
}
