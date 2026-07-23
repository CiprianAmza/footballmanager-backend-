package com.footballmanagergamesimulator.person;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.user.CareerRole;
import com.footballmanagergamesimulator.user.CareerControlConflictException;
import com.footballmanagergamesimulator.user.User;
import com.footballmanagergamesimulator.user.UserRepository;
import com.footballmanagergamesimulator.util.TypeNames;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class PersonProfileService {

    private final PersonProfileRepository profileRepository;
    private final UserRepository userRepository;
    private final HumanRepository humanRepository;

    public PersonProfileService(PersonProfileRepository profileRepository,
                                UserRepository userRepository,
                                HumanRepository humanRepository) {
        this.profileRepository = profileRepository;
        this.userRepository = userRepository;
        this.humanRepository = humanRepository;
    }

    @Transactional
    public PersonProfile createForUser(User user, String displayName) {
        return profileRepository.findByUserId(user.getId()).orElseGet(() -> {
            PersonProfile profile = new PersonProfile();
            profile.setUserId(user.getId());
            profile.setCareerType(user.getCareerRole() == CareerRole.CHAIRMAN
                    ? CareerType.CHAIRMAN : CareerType.MANAGER);
            profile.setControlType(ControlType.USER);
            profile.setDisplayName(normalizedName(displayName, user.getUsername(), "user-" + user.getId()));
            profile.setActive(true);
            return profileRepository.save(profile);
        });
    }

    @Transactional
    public PersonProfile attachManager(User user, Human manager) {
        PersonProfile humanProfile = profileRepository.findByHumanIdForUpdate(manager.getId()).orElse(null);
        if (humanProfile != null && humanProfile.getUserId() != null
                && !humanProfile.getUserId().equals(user.getId())) {
            throw new CareerControlConflictException("Manager identity is controlled by another user");
        }

        PersonProfile profile = profileRepository.findByUserIdForUpdate(user.getId())
                .orElseGet(() -> createForUser(user, manager.getName()));
        if (profile.getHumanId() != null && !profile.getHumanId().equals(manager.getId())) {
            throw new CareerControlConflictException("User already controls another manager identity");
        }
        if (humanProfile != null && humanProfile.getId() != profile.getId()) {
            // The Human profile is AI-only. Preserve the user's canonical
            // profile and remove only the unowned AI duplicate.
            profileRepository.delete(humanProfile);
            profileRepository.flush();
        }
        profile.setHumanId(manager.getId());
        profile.setCareerType(CareerType.MANAGER);
        profile.setControlType(ControlType.USER);
        profile.setDisplayName(normalizedName(manager.getName(), user.getUsername(), "manager-" + manager.getId()));
        return profileRepository.save(profile);
    }

    public PersonProfile requireForUser(User user) {
        return profileRepository.findByUserId(user.getId())
                .orElseThrow(() -> new IllegalStateException("Person profile is missing"));
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(10)
    @Transactional
    public void backfillOnStartup() {
        backfill();
    }

    @Transactional
    public PersonProfile ensureForHuman(Human human) {
        if (human.getTypeId() != TypeNames.PLAYER_TYPE && human.getTypeId() != TypeNames.MANAGER_TYPE) {
            throw new IllegalArgumentException("Only player and manager identities have personal economy profiles");
        }
        return profileRepository.findByHumanId(human.getId()).orElseGet(() -> {
            PersonProfile profile = new PersonProfile();
            profile.setHumanId(human.getId());
            profile.setCareerType(human.getTypeId() == TypeNames.MANAGER_TYPE
                    ? CareerType.MANAGER : CareerType.PLAYER);
            profile.setControlType(ControlType.AI);
            profile.setDisplayName(normalizedName(human.getName(), null, "human-" + human.getId()));
            profile.setActive(!human.isRetired());
            profile.setRetired(human.isRetired());
            return profileRepository.save(profile);
        });
    }

    @Transactional
    public void backfill() {
        Map<Long, User> usersByManager = new HashMap<>();
        List<User> users = userRepository.findAll();
        Map<Long, PersonProfile> profilesByHuman = new HashMap<>();
        Map<Integer, PersonProfile> profilesByUser = new HashMap<>();
        for (PersonProfile profile : profileRepository.findAll()) {
            if (profile.getHumanId() != null) profilesByHuman.put(profile.getHumanId(), profile);
            if (profile.getUserId() != null) profilesByUser.put(profile.getUserId(), profile);
        }
        for (User user : users) {
            if (user.getCareerRole() == null) user.setCareerRole(CareerRole.MANAGER);
            if (user.getRoles() == null || user.getRoles().isBlank()) user.setRoles("USER");
            if (user.getManagerId() != null) {
                usersByManager.merge(user.getManagerId(), user,
                        (first, second) -> first.getId() <= second.getId() ? first : second);
            }
        }

        for (Human human : humanRepository.findAll()) {
            if (human.getTypeId() != TypeNames.PLAYER_TYPE && human.getTypeId() != TypeNames.MANAGER_TYPE) {
                PersonProfile unsupported = profilesByHuman.remove(human.getId());
                if (unsupported != null && unsupported.getUserId() == null) {
                    profileRepository.delete(unsupported);
                }
                continue;
            }
            User linkedUser = usersByManager.get(human.getId());
            PersonProfile humanProfile = profilesByHuman.get(human.getId());
            PersonProfile userProfile = linkedUser == null ? null : profilesByUser.get(linkedUser.getId());
            PersonProfile profile;
            if (userProfile != null) {
                if (userProfile.getHumanId() != null && !userProfile.getHumanId().equals(human.getId())) {
                    throw new CareerControlConflictException(
                            "User profile is already linked to another Human identity");
                }
                if (humanProfile != null && humanProfile.getId() != userProfile.getId()) {
                    if (humanProfile.getUserId() != null
                            && !humanProfile.getUserId().equals(linkedUser.getId())) {
                        throw new CareerControlConflictException(
                                "Human identity is controlled by another user");
                    }
                    profileRepository.delete(humanProfile);
                    profileRepository.flush();
                    profilesByHuman.remove(human.getId());
                }
                profile = userProfile;
            } else {
                profile = humanProfile;
            }
            if (profile == null) profile = new PersonProfile();
            profile.setHumanId(human.getId());
            profile.setUserId(linkedUser != null ? linkedUser.getId() : null);
            profile.setCareerType(human.getTypeId() == TypeNames.MANAGER_TYPE ? CareerType.MANAGER : CareerType.PLAYER);
            profile.setControlType(linkedUser != null ? ControlType.USER : ControlType.AI);
            profile.setDisplayName(normalizedName(human.getName(),
                    linkedUser != null ? linkedUser.getUsername() : null, "human-" + human.getId()));
            profile.setActive(!human.isRetired());
            profile.setRetired(human.isRetired());
            profile = profileRepository.save(profile);
            profilesByHuman.put(human.getId(), profile);
            if (linkedUser != null) profilesByUser.put(linkedUser.getId(), profile);
        }

        for (User user : users) {
            if (!profilesByUser.containsKey(user.getId())) {
                PersonProfile profile = createForUser(user, user.getFirstName() + " " + user.getLastName());
                profilesByUser.put(user.getId(), profile);
            }
        }
    }

    private static String normalizedName(String first, String second, String fallback) {
        for (String candidate : new String[]{first, second}) {
            if (candidate != null && !candidate.trim().isEmpty() && !"null null".equalsIgnoreCase(candidate.trim())) {
                return candidate.trim();
            }
        }
        return fallback;
    }
}
