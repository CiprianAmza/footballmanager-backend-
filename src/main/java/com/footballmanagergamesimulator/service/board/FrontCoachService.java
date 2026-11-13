package com.footballmanagergamesimulator.service.board;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.footballmanagergamesimulator.controller.TacticController;
import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.repository.*;
import com.footballmanagergamesimulator.service.FanSocialFeedService;
import com.footballmanagergamesimulator.service.RelationService;
import com.footballmanagergamesimulator.user.UserContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * The front coach's revolt (design DESIGN_START_CARIERA §4, decided 2026-11-13). His loyalty is the staff
 * loyalty of §5.2; his personality bends it (a proud one takes losses twice as hard, a mercenary half). Below 30
 * he revolts: he fields his own team sheet and refuses the manager's substitutions, until he is back above 40.
 * Each month the press may find out he is only a front (10%, 30% during a revolt), once.
 */
@Service
public class FrontCoachService {

    public static final int REVOLT_BELOW = 30;
    public static final int CALM_ABOVE = 40;
    public static final int PROUD_EXTRA_LOSS = -2;
    /** Without a director his loyalty starts here (his own ambition, not a director, pulls him away). */
    public static final int NO_DIRECTOR_START = 60;
    public static final int PROUD_MONTHLY_WITHOUT_DIRECTOR = -1;
    public static final double LEAK = 0.10;
    public static final double LEAK_DURING_REVOLT = 0.30;
    public static final int SUPPORTERS_OPINION_ON_LEAK = -10;

    private final FrontCoachRepository frontRepository;
    private final StaffLoyaltyRepository loyaltyRepository;
    private final LicenceService licenceService;
    private final BoardTrustService boardTrust;
    private final ClubBoardRepository boardRepository;
    private final PersonalizedTacticRepository tacticRepository;
    private final HumanRepository humanRepository;
    private final TeamRepository teamRepository;
    private final ManagerInboxRepository inboxRepository;
    private final GameCalendarRepository calendarRepository;
    private final RelationService relationService;
    private final ObjectProvider<TacticController> tacticController;
    private final ObjectProvider<UserContext> userContext;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Random rng;

    @Autowired
    public FrontCoachService(FrontCoachRepository frontRepository, StaffLoyaltyRepository loyaltyRepository,
                             LicenceService licenceService, BoardTrustService boardTrust,
                             ClubBoardRepository boardRepository, PersonalizedTacticRepository tacticRepository,
                             HumanRepository humanRepository, TeamRepository teamRepository,
                             ManagerInboxRepository inboxRepository, GameCalendarRepository calendarRepository,
                             RelationService relationService, ObjectProvider<TacticController> tacticController,
                             ObjectProvider<UserContext> userContext) {
        this(frontRepository, loyaltyRepository, licenceService, boardTrust, boardRepository, tacticRepository,
                humanRepository, teamRepository, inboxRepository, calendarRepository, relationService,
                tacticController, userContext, new Random());
    }

    FrontCoachService(FrontCoachRepository frontRepository, StaffLoyaltyRepository loyaltyRepository,
                      LicenceService licenceService, BoardTrustService boardTrust,
                      ClubBoardRepository boardRepository, PersonalizedTacticRepository tacticRepository,
                      HumanRepository humanRepository, TeamRepository teamRepository,
                      ManagerInboxRepository inboxRepository, GameCalendarRepository calendarRepository,
                      RelationService relationService, ObjectProvider<TacticController> tacticController,
                      ObjectProvider<UserContext> userContext, Random rng) {
        this.frontRepository = frontRepository;
        this.loyaltyRepository = loyaltyRepository;
        this.licenceService = licenceService;
        this.boardTrust = boardTrust;
        this.boardRepository = boardRepository;
        this.tacticRepository = tacticRepository;
        this.humanRepository = humanRepository;
        this.teamRepository = teamRepository;
        this.inboxRepository = inboxRepository;
        this.calendarRepository = calendarRepository;
        this.relationService = relationService;
        this.tacticController = tacticController;
        this.userContext = userContext;
        this.rng = rng;
    }

    /** A manager takes the club (after staff loyalty is set): at a club without a director the front coach starts at 60. */
    @Transactional
    public void managerAppointed(long teamId) {
        frontRepository.findByTeamId(teamId).ifPresent(row -> {
            frontRepository.delete(row);
            frontRepository.flush();
        });
        current(teamId).ifPresent(front -> {
            boolean director = boardRepository.findByTeamId(teamId).map(ClubBoard::hasDirector).orElse(false);
            if (!director) loyaltyRepository.findByStaffId(front.getCoachId()).ifPresent(row -> {
                row.setLoyalty(NO_DIRECTOR_START);
                loyaltyRepository.save(row);
            });
        });
    }

    /** After a match (staff loyalty already moved by the result): his personality bends a loss, then he may revolt. */
    @Transactional
    public void matchPlayed(long teamId, int goalsFor, int goalsAgainst) {
        Optional<FrontCoach> front = current(teamId);
        if (front.isEmpty()) return;
        FrontCoach row = front.get();
        if (goalsFor < goalsAgainst) {
            if (row.isProud()) moveLoyalty(row.getCoachId(), PROUD_EXTRA_LOSS);
            else if (rng.nextBoolean()) moveLoyalty(row.getCoachId(), 1);
        }
        evaluate(row);
    }

    /** Monthly: a proud front coach without a director drifts away; the secret may leak. */
    @Transactional
    public void monthPassed() {
        UserContext users = userContext.getIfAvailable();
        if (users == null) return;
        for (long teamId : users.getAllHumanTeamIds()) {
            Optional<FrontCoach> front = current(teamId);
            if (front.isEmpty()) continue;
            FrontCoach row = front.get();
            boolean director = boardRepository.findByTeamId(teamId).map(ClubBoard::hasDirector).orElse(false);
            if (!director && row.isProud()) moveLoyalty(row.getCoachId(), PROUD_MONTHLY_WITHOUT_DIRECTOR);
            evaluate(row);
            if (!row.isDiscovered() && rng.nextDouble() < (row.isRevolting() ? LEAK_DURING_REVOLT : LEAK)) leak(row);
        }
    }

    public boolean isRevolting(long teamId) {
        return frontRepository.findByTeamId(teamId).map(FrontCoach::isRevolting).orElse(false);
    }

    public String refusal(long teamId) {
        String name = frontRepository.findByTeamId(teamId).flatMap(r -> humanRepository.findById(r.getCoachId()))
                .map(Human::getName).orElse("The head coach");
        return name + " ignores your instructions. On paper, he picks the team.";
    }

    /** The front coach now, kept in step with the licence rule; ends a revolt whose cause is gone. */
    @Transactional
    public Optional<FrontCoach> current(long teamId) {
        Optional<FrontCoach> existing = frontRepository.findByTeamId(teamId);
        long manager = boardTrust.managerOf(teamId);
        Optional<Human> coach = manager == 0 || licenceService.qualifies(manager, teamId)
                ? Optional.empty() : licenceService.frontCoach(teamId);
        if (coach.isEmpty()) {
            existing.ifPresent(row -> {
                if (row.isRevolting()) endRevolt(row, false);
                frontRepository.delete(row);
            });
            return Optional.empty();
        }
        FrontCoach row = existing.orElseGet(() -> {
            FrontCoach fresh = new FrontCoach();
            fresh.setTeamId(teamId);
            return fresh;
        });
        if (row.getCoachId() != coach.get().getId()) {
            if (row.isRevolting()) endRevolt(row, false);
            row.setCoachId(coach.get().getId());
            row.setProud(rng.nextBoolean());
            row.setRevolting(false);
            row = frontRepository.save(row);
        }
        return Optional.of(row);
    }

    void evaluate(FrontCoach row) {
        int loyalty = loyalty(row.getCoachId());
        if (!row.isRevolting() && loyalty < REVOLT_BELOW) startRevolt(row);
        else if (row.isRevolting() && loyalty > CALM_ABOVE) endRevolt(row, true);
        else if (row.isRevolting()) fieldHisSheet(row.getTeamId());
    }

    private void startRevolt(FrontCoach row) {
        tacticRepository.findPersonalizedTacticByTeamId(row.getTeamId()).ifPresent(t -> {
            row.setSavedFirst11(t.getFirst11());
            row.setSavedTactic(t.getTactic());
        });
        row.setRevolting(true);
        frontRepository.save(row);
        fieldHisSheet(row.getTeamId());
        notify(row.getTeamId(), "board", "Your head coach has turned on you",
                coachName(row) + " has had enough of putting his name to your decisions. From now on he picks the team"
                        + " and makes the changes himself.");
    }

    private void endRevolt(FrontCoach row, boolean announce) {
        tacticRepository.findPersonalizedTacticByTeamId(row.getTeamId()).ifPresent(t -> {
            if (row.getSavedFirst11() != null) t.setFirst11(row.getSavedFirst11());
            if (row.getSavedTactic() != null) t.setTactic(row.getSavedTactic());
            tacticRepository.save(t);
        });
        row.setRevolting(false);
        row.setSavedFirst11(null);
        row.setSavedTactic(null);
        frontRepository.save(row);
        if (announce) notify(row.getTeamId(), "board", "Your head coach is back on side",
                coachName(row) + " is listening to you again. Your team sheet is back in use.");
    }

    /** He fields the eleven the assistant would pick for the formation in use. */
    private void fieldHisSheet(long teamId) {
        TacticController tactics = tacticController.getIfAvailable();
        PersonalizedTactic t = tacticRepository.findPersonalizedTacticByTeamId(teamId).orElse(null);
        if (tactics == null || t == null) return;
        try {
            String formation = t.getTactic() == null || t.getTactic().isBlank() ? "442" : t.getTactic();
            t.setFirst11(mapper.writeValueAsString(tactics.askAssistant(teamId, formation)));
            tacticRepository.save(t);
        } catch (Exception keepTheSheet) {
            // Without a sheet of his own the club keeps the current one.
        }
    }

    /** The press finds out who really runs the team: an article, the supporters' posts, their opinion of the manager. */
    void leak(FrontCoach row) {
        row.setDiscovered(true);
        frontRepository.save(row);
        long teamId = row.getTeamId();
        String team = teamRepository.findById(teamId).map(Team::getName).orElse("the club");
        long manager = boardTrust.managerOf(teamId);
        String managerName = humanRepository.findById(manager).map(Human::getName).orElse("the manager");
        notify(teamId, "press", "Revealed: who really runs " + team + "?",
                "Sources inside " + team + " confirm that " + coachName(row) + " is head coach in name only. "
                        + managerName + ", who lacks the licence the league requires, picks the team.");
        String[][] posts = {
                {"ANGRY", "So " + coachName(row) + " is just a name on the team sheet? We were lied to."},
                {"HARSH", "A manager without the licence, hiding behind a front man. What else is the club hiding?"},
                {"ANALYTICAL", "Plenty of clubs work around the licence rule. Results will decide if anyone cares."},
                {"LOYAL", "I back whoever wins us games, badge or no badge. But tell us the truth next time."}};
        int season = season();
        for (int i = 0; i < posts.length; i++) {
            ManagerInbox post = new ManagerInbox();
            post.setTeamId(teamId);
            post.setSeasonNumber(season);
            post.setTitle("@" + team.toLowerCase().replaceAll("[^a-z0-9]", "") + "_fan" + (i + 1));
            post.setContent(posts[i][0] + "\nThe front coach revelation\n" + posts[i][1]);
            post.setCategory(FanSocialFeedService.CATEGORY);
            post.setRead(true);
            post.setCreatedAt(System.currentTimeMillis() + i);
            post.setAudience(InboxAudience.MANAGER);
            inboxRepository.save(post);
        }
        // There is no supporter morale yet: their opinion of the manager is kept for when there is.
        if (manager > 0)
            relationService.change(RelationParty.CLUB, teamId, RelationParty.MANAGER, manager, SUPPORTERS_OPINION_ON_LEAK);
    }

    private int loyalty(long coachId) {
        return loyaltyRepository.findByStaffId(coachId).map(StaffLoyalty::getLoyalty).orElse(StaffLoyaltyService.BROUGHT);
    }

    private void moveLoyalty(long coachId, int delta) {
        loyaltyRepository.findByStaffId(coachId).ifPresent(row -> {
            row.setLoyalty(Math.max(0, Math.min(100, row.getLoyalty() + delta)));
            loyaltyRepository.save(row);
        });
    }

    private String coachName(FrontCoach row) {
        return humanRepository.findById(row.getCoachId()).map(Human::getName).orElse("The head coach");
    }

    private void notify(long teamId, String category, String title, String content) {
        ManagerInbox inbox = new ManagerInbox();
        inbox.setTeamId(teamId);
        inbox.setSeasonNumber(season());
        inbox.setTitle(title);
        inbox.setContent(content);
        inbox.setCategory(category);
        inbox.setRead(false);
        inbox.setCreatedAt(System.currentTimeMillis());
        inboxRepository.save(inbox);
    }

    private int season() {
        return calendarRepository.findTopByOrderBySeasonDesc().map(GameCalendar::getSeason).orElse(1);
    }
}
