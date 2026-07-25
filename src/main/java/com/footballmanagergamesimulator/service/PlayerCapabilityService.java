package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.compartment.PlayerPosition;
import com.footballmanagergamesimulator.compartment.PlayerRole;
import com.footballmanagergamesimulator.compartment.adapter.PlayerCapabilitySnapshot;
import com.footballmanagergamesimulator.compartment.adapter.PlayerCapabilityResolver;
import com.footballmanagergamesimulator.compartment.adapter.PositionRoleKey;
import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.PlayerFootProfile;
import com.footballmanagergamesimulator.model.PlayerPositionFamiliarity;
import com.footballmanagergamesimulator.model.PlayerRoleFamiliarity;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.PlayerFootProfileRepository;
import com.footballmanagergamesimulator.repository.PlayerPositionFamiliarityRepository;
import com.footballmanagergamesimulator.repository.PlayerRoleFamiliarityRepository;
import com.footballmanagergamesimulator.util.TypeNames;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class PlayerCapabilityService {
    private final HumanRepository humanRepository;
    private final PlayerPositionFamiliarityRepository positionRepository;
    private final PlayerRoleFamiliarityRepository roleRepository;
    private final PlayerFootProfileRepository footRepository;
    private final PlayerCapabilityResolver capabilityResolver;
    private final ThreadLocal<Map<Long, PlayerCapabilitySnapshot>> preloadedScope = new ThreadLocal<>();

    public PlayerCapabilityService(HumanRepository humanRepository,
                                   PlayerPositionFamiliarityRepository positionRepository,
                                   PlayerRoleFamiliarityRepository roleRepository,
                                   PlayerFootProfileRepository footRepository,
                                   MatchEngineConfig matchEngineConfig) {
        this.humanRepository = humanRepository;
        this.positionRepository = positionRepository;
        this.roleRepository = roleRepository;
        this.footRepository = footRepository;
        this.capabilityResolver = new PlayerCapabilityResolver(matchEngineConfig);
    }

    @Transactional(readOnly = true)
    public PlayerCapabilitySnapshot load(long playerId) {
        return loadAll(List.of(playerId)).get(playerId);
    }

    @Transactional(readOnly = true)
    public Map<Long, PlayerCapabilitySnapshot> loadAll(Collection<Long> playerIds) {
        List<Long> orderedIds = playerIds == null ? List.of() : playerIds.stream()
                .peek(id -> { if (id == null || id <= 0) throw new IllegalArgumentException("player id must be positive"); })
                .distinct()
                .sorted()
                .toList();
        if (orderedIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, PlayerCapabilitySnapshot> preloaded = preloadedScope.get();
        if (preloaded != null && orderedIds.stream().allMatch(preloaded::containsKey)) {
            LinkedHashMap<Long, PlayerCapabilitySnapshot> result = new LinkedHashMap<>();
            orderedIds.forEach(playerId -> result.put(playerId, preloaded.get(playerId)));
            return java.util.Collections.unmodifiableMap(result);
        }

        return loadAllFromStore(orderedIds);
    }

    /** Preload immutable matchday capability snapshots once for the current simulation thread. */
    @Transactional(readOnly = true)
    public void preloadForCurrentThread(Collection<Long> playerIds) {
        preloadedScope.remove();
        List<Long> orderedIds = playerIds == null ? List.of() : playerIds.stream()
                .peek(id -> { if (id == null || id <= 0) throw new IllegalArgumentException("player id must be positive"); })
                .distinct()
                .sorted()
                .toList();
        preloadedScope.set(orderedIds.isEmpty() ? Map.of() : loadAllFromStore(orderedIds));
    }

    public void clearPreloadedForCurrentThread() {
        preloadedScope.remove();
    }

    private Map<Long, PlayerCapabilitySnapshot> loadAllFromStore(List<Long> orderedIds) {

        Map<Long, Human> humans = new HashMap<>();
        humanRepository.findAllById(orderedIds).forEach(human -> humans.put(human.getId(), human));

        Map<Long, List<PlayerPositionFamiliarity>> positions = groupPositions(
                positionRepository.findAllByPlayerIdIn(orderedIds));
        Map<Long, List<PlayerRoleFamiliarity>> roles = groupRoles(
                roleRepository.findAllByPlayerIdIn(orderedIds));
        Map<Long, PlayerFootProfile> feet = new HashMap<>();
        for (PlayerFootProfile foot : footRepository.findAllByPlayerIdIn(orderedIds)) {
            if (feet.put(foot.getPlayerId(), foot) != null) {
                throw new IllegalStateException("duplicate foot profile for player " + foot.getPlayerId());
            }
        }

        LinkedHashMap<Long, PlayerCapabilitySnapshot> result = new LinkedHashMap<>();
        for (Long playerId : orderedIds) {
            Human human = humans.get(playerId);
            if (human == null) {
                throw new IllegalArgumentException("player " + playerId + " does not exist");
            }
            if (human.getTypeId() != TypeNames.PLAYER_TYPE) {
                throw new IllegalArgumentException("human " + playerId + " is not a player");
            }
            result.put(playerId, snapshot(playerId, human, positions.getOrDefault(playerId, List.of()),
                    roles.getOrDefault(playerId, List.of()), feet.get(playerId)));
        }
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(result));
    }

    public int fallbackPositionFamiliarity(String naturalPosition, PlayerPosition usedPosition) {
        return capabilityResolver.fallbackPositionFamiliarity(naturalPosition, usedPosition);
    }

    public int positionFamiliarityOrFallback(PlayerCapabilitySnapshot snapshot, PlayerPosition usedPosition) {
        return capabilityResolver.positionFamiliarityOrFallback(snapshot, usedPosition);
    }

    public int roleFamiliarityOrFallback(PlayerCapabilitySnapshot snapshot, PlayerPosition position, PlayerRole role) {
        return capabilityResolver.roleFamiliarityOrFallback(snapshot, position, role);
    }

    private PlayerCapabilitySnapshot snapshot(long playerId, Human human,
                                              List<PlayerPositionFamiliarity> positionRows,
                                              List<PlayerRoleFamiliarity> roleRows,
                                              PlayerFootProfile footProfile) {
        EnumMap<PlayerPosition, Integer> positionMap = new EnumMap<>(PlayerPosition.class);
        PlayerPosition primary = null;
        for (PlayerPositionFamiliarity row : sortedPositions(positionRows)) {
            PlayerPosition position = PlayerPosition.require(row.getPositionCode());
            requireFamiliarity(row.getFamiliarity(), "position familiarity");
            if (positionMap.put(position, row.getFamiliarity()) != null) {
                throw new IllegalStateException("duplicate position familiarity for player " + playerId);
            }
            if (row.isPrimaryPosition()) {
                if (primary != null) {
                    throw new IllegalStateException("multiple primary positions for player " + playerId);
                }
                primary = position;
            }
        }

        boolean positionFallback = false;
        if (positionMap.isEmpty()) {
            PlayerPosition legacy = PlayerPosition.parse(human.getPosition()).orElse(null);
            if (legacy != null) {
                positionMap.put(legacy, 20);
                primary = legacy;
            }
            positionFallback = true;
        }

        Map<PositionRoleKey, Integer> roleMap = new LinkedHashMap<>();
        for (PlayerRoleFamiliarity row : sortedRoles(roleRows)) {
            PositionRoleKey key = PositionRoleKey.ofCodes(row.getPositionCode(), row.getRoleCode());
            requireFamiliarity(row.getFamiliarity(), "role familiarity");
            if (roleMap.put(key, row.getFamiliarity()) != null) {
                throw new IllegalStateException("duplicate role familiarity for player " + playerId);
            }
        }

        FootRatings footRatings = footProfile == null
                ? legacyFootRatings(human.getPreferredFoot())
                : new FootRatings(footProfile.getLeftFootRating(), footProfile.getRightFootRating());
        requireFamiliarity(footRatings.left(), "left foot rating");
        requireFamiliarity(footRatings.right(), "right foot rating");

        return new PlayerCapabilitySnapshot(playerId, primary, positionMap, roleMap,
                footRatings.left(), footRatings.right(), positionFallback, roleRows.isEmpty(), footProfile == null);
    }

    private Map<Long, List<PlayerPositionFamiliarity>> groupPositions(List<PlayerPositionFamiliarity> rows) {
        Map<Long, List<PlayerPositionFamiliarity>> grouped = new HashMap<>();
        for (PlayerPositionFamiliarity row : rows) {
            grouped.computeIfAbsent(row.getPlayerId(), ignored -> new ArrayList<>()).add(row);
        }
        return grouped;
    }

    private Map<Long, List<PlayerRoleFamiliarity>> groupRoles(List<PlayerRoleFamiliarity> rows) {
        Map<Long, List<PlayerRoleFamiliarity>> grouped = new HashMap<>();
        for (PlayerRoleFamiliarity row : rows) {
            grouped.computeIfAbsent(row.getPlayerId(), ignored -> new ArrayList<>()).add(row);
        }
        return grouped;
    }

    private static List<PlayerPositionFamiliarity> sortedPositions(List<PlayerPositionFamiliarity> rows) {
        return rows.stream()
                .sorted(Comparator.comparing(PlayerPositionFamiliarity::getPositionCode)
                        .thenComparingLong(PlayerPositionFamiliarity::getId))
                .toList();
    }

    private static List<PlayerRoleFamiliarity> sortedRoles(List<PlayerRoleFamiliarity> rows) {
        return rows.stream()
                .sorted(Comparator.comparing(PlayerRoleFamiliarity::getPositionCode)
                        .thenComparing(PlayerRoleFamiliarity::getRoleCode)
                        .thenComparingLong(PlayerRoleFamiliarity::getId))
                .toList();
    }

    public static FootRatings legacyFootRatings(String preferredFoot) {
        if ("Left".equals(preferredFoot)) {
            return new FootRatings(20, 8);
        }
        if ("Both".equals(preferredFoot)) {
            return new FootRatings(16, 16);
        }
        return new FootRatings(8, 20);
    }

    private static void requireFamiliarity(int value, String field) {
        if (value < 1 || value > 20) {
            throw new IllegalArgumentException(field + " must be in [1,20]");
        }
    }

    public record FootRatings(int left, int right) {}
}
