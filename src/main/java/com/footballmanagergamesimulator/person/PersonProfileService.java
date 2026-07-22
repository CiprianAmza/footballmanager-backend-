package com.footballmanagergamesimulator.person;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.user.CareerRole;
import com.footballmanagergamesimulator.user.User;
import com.footballmanagergamesimulator.user.UserRepository;
import com.footballmanagergamesimulator.util.TypeNames;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
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
        PersonProfile profile = profileRepository.findByUserId(user.getId())
                .orElseGet(() -> createForUser(user, manager.getName()));
        profileRepository.findByHumanId(manager.getId())
                .filter(other -> other.getId() != profile.getId())
                .ifPresent(other -> {
                    profileRepository.delete(other);
                    profileRepository.flush();
                });
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
    @Transactional
    public void backfillOnStartup() {
        backfill();
    }

    @Transactional
    public void backfill() {
        Map<Long, User> usersByManager = new HashMap<>();
        for (User user : userRepository.findAll()) {
            if (user.getCareerRole() == null) user.setCareerRole(CareerRole.MANAGER);
            if (user.getRoles() == null || user.getRoles().isBlank()) user.setRoles("USER");
            if (user.getManagerId() != null) {
                usersByManager.merge(user.getManagerId(), user,
                        (first, second) -> first.getId() <= second.getId() ? first : second);
            }
        }

        for (Human human : humanRepository.findAll()) {
            User linkedUser = usersByManager.get(human.getId());
            PersonProfile humanProfile = profileRepository.findByHumanId(human.getId()).orElse(null);
            PersonProfile userProfile = linkedUser == null ? null
                    : profileRepository.findByUserId(linkedUser.getId()).orElse(null);
            PersonProfile profile;
            if (userProfile != null) {
                if (humanProfile != null && humanProfile.getId() != userProfile.getId()) {
                    profileRepository.delete(humanProfile);
                    profileRepository.flush();
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
            profileRepository.save(profile);
        }

        for (User user : userRepository.findAll()) {
            if (profileRepository.findByUserId(user.getId()).isEmpty()) {
                createForUser(user, user.getFirstName() + " " + user.getLastName());
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
