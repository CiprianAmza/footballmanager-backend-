package com.footballmanagergamesimulator.user;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.economy.PersonalAccountingService;
import com.footballmanagergamesimulator.economy.RegentEconomyProperties;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.person.CareerType;
import com.footballmanagergamesimulator.person.ControlType;
import com.footballmanagergamesimulator.person.PersonProfile;
import com.footballmanagergamesimulator.person.PersonProfileRepository;
import com.footballmanagergamesimulator.person.PersonProfileService;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.service.JobOfferService;
import com.footballmanagergamesimulator.util.TypeNames;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:regent-onboarding-concurrency;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;LOCK_TIMEOUT=10000",
        "spring.datasource.username=sa",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({CareerOnboardingService.class, PersonProfileService.class,
        PersonalAccountingService.class, RegentEconomyProperties.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CareerOnboardingConcurrencyTest {

    @Autowired private CareerOnboardingService service;
    @Autowired private UserRepository users;
    @Autowired private TeamRepository teams;
    @Autowired private HumanRepository humans;
    @Autowired private PersonProfileRepository profiles;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockBean private JobOfferService jobOfferService;

    private long teamId;
    private int firstUserId;
    private int secondUserId;

    @BeforeEach
    void seedTwoUnassignedUsersAndOneAiManager() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Team team = new Team();
            team.setName("Concurrency FC");
            team = teams.saveAndFlush(team);
            teamId = team.getId();

            Human manager = new Human();
            manager.setName("AI manager");
            manager.setTeamId(teamId);
            manager.setTypeId(TypeNames.MANAGER_TYPE);
            manager = humans.saveAndFlush(manager);

            User first = users.saveAndFlush(user("first-concurrent"));
            User second = users.saveAndFlush(user("second-concurrent"));
            firstUserId = first.getId();
            secondUserId = second.getId();
            profiles.saveAndFlush(profile(first));
            profiles.saveAndFlush(profile(second));

            PersonProfile ai = new PersonProfile();
            ai.setHumanId(manager.getId());
            ai.setCareerType(CareerType.MANAGER);
            ai.setControlType(ControlType.AI);
            ai.setDisplayName("AI manager");
            ai.setActive(true);
            profiles.saveAndFlush(ai);
        });
    }

    @Test
    void concurrentSelectionOfSameTeamAndHumanHasExactlyOneWinner() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            futures.add(executor.submit(() -> attempt(firstUserId, "First", ready, start)));
            futures.add(executor.submit(() -> attempt(secondUserId, "Second", ready, start)));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Object> results = List.of(futures.get(0).get(15, TimeUnit.SECONDS),
                    futures.get(1).get(15, TimeUnit.SECONDS));
            assertThat(results.stream().filter(Map.class::isInstance).count()).isEqualTo(1);
            assertThat(results.stream().filter(CareerControlConflictException.class::isInstance).count()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }

        List<User> controllers = users.findAllByTeamId(teamId);
        assertThat(controllers).hasSize(1);
        int winnerId = controllers.get(0).getId();
        int loserId = winnerId == firstUserId ? secondUserId : firstUserId;
        Human manager = humans.findAllByTeamIdAndTypeId(teamId, TypeNames.MANAGER_TYPE).get(0);
        assertThat(profiles.findByHumanId(manager.getId())).get()
                .extracting(PersonProfile::getUserId).isEqualTo(winnerId);
        assertThat(profiles.findByUserId(loserId)).get()
                .extracting(PersonProfile::getHumanId).isNull();
    }

    private Object attempt(int userId, String name, CountDownLatch ready, CountDownLatch start)
            throws InterruptedException {
        User actor = users.findById(userId).orElseThrow();
        ready.countDown();
        start.await(5, TimeUnit.SECONDS);
        try {
            return service.setupManager(actor, new ManagerSetupRequest(name, 40, teamId, false));
        } catch (RuntimeException exception) {
            return exception;
        }
    }

    private User user(String username) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@example.test");
        user.setPassword("$2a$10$012345678901234567890u0123456789012345678901234567890");
        user.setRoles("USER");
        user.setCareerRole(CareerRole.MANAGER);
        user.setActive(true);
        return user;
    }

    private PersonProfile profile(User user) {
        PersonProfile profile = new PersonProfile();
        profile.setUserId(user.getId());
        profile.setCareerType(CareerType.MANAGER);
        profile.setControlType(ControlType.USER);
        profile.setDisplayName(user.getUsername());
        profile.setActive(true);
        return profile;
    }
}
