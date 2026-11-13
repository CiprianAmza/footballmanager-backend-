package com.footballmanagergamesimulator.service.press;

import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.repository.*;
import com.footballmanagergamesimulator.user.CoachingLicence;
import com.footballmanagergamesimulator.user.PlayingCareer;
import com.footballmanagergamesimulator.service.NationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * The press article on a human manager's appointment (design DESIGN_START_CARIERA §6.1, decided 2026-11-12):
 * headline, who he is, the club and the decision, the club's context, the pundits' comments and how long his
 * predecessors lasted. Built from rotated, pre-written fragments; delivered to the club's inbox as "press".
 * (The section on a club whose director general is in conflict comes with step 8.)
 */
@Service
public class AppointmentArticleService {

    static final String CATEGORY = "press";
    static final int RECENT_MANAGERS = 5;

    private final PressFragmentService fragments;
    private final AnalystService analysts;
    private final TeamRepository teamRepository;
    private final CompetitionRepository competitionRepository;
    private final CompetitionHistoryRepository historyRepository;
    private final ManagerHistoryRepository managerHistoryRepository;
    private final ManagerBackgroundRepository backgroundRepository;
    private final GameCalendarRepository calendarRepository;
    private final ManagerInboxRepository inboxRepository;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.footballmanagergamesimulator.service.RelationService relationService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.footballmanagergamesimulator.service.board.LicenceService licenceService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ClubBoardRepository boardRepository;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private HumanRepository humanRepository;

    public AppointmentArticleService(PressFragmentService fragments, AnalystService analysts,
                                     TeamRepository teamRepository, CompetitionRepository competitionRepository,
                                     CompetitionHistoryRepository historyRepository,
                                     ManagerHistoryRepository managerHistoryRepository,
                                     ManagerBackgroundRepository backgroundRepository,
                                     GameCalendarRepository calendarRepository,
                                     ManagerInboxRepository inboxRepository) {
        this.fragments = fragments;
        this.analysts = analysts;
        this.teamRepository = teamRepository;
        this.competitionRepository = competitionRepository;
        this.historyRepository = historyRepository;
        this.managerHistoryRepository = managerHistoryRepository;
        this.backgroundRepository = backgroundRepository;
        this.calendarRepository = calendarRepository;
        this.inboxRepository = inboxRepository;
    }

    /** The article as written: headline and paragraphs. */
    public record Article(String headline, List<String> paragraphs) {
        public String body() {
            return String.join("\n\n", paragraphs);
        }
    }

    /** Writes the article for {@code manager} joining {@code clubId} and puts it in the club's inbox. */
    @Transactional
    public Optional<Article> publish(Human manager, long clubId) {
        Team club = teamRepository.findById(clubId).orElse(null);
        if (manager == null || club == null) return Optional.empty();
        fragments.ensureLoaded();
        analysts.ensureAnalysts();
        GameCalendar calendar = calendarRepository.findTopByOrderBySeasonDesc().orElse(null);
        int season = calendar == null ? 1 : calendar.getSeason();
        int day = calendar == null ? 1 : calendar.getCurrentDay();
        int today = season * 365 + day;
        Random rng = new Random(Objects.hash(manager.getId(), clubId, today));

        Article article = write(manager, club, today, rng);
        ManagerInbox inbox = new ManagerInbox();
        inbox.setTeamId(clubId);
        inbox.setSeasonNumber(season);
        inbox.setRoundNumber(day);
        inbox.setTitle(article.headline());
        inbox.setContent(article.body());
        inbox.setCategory(CATEGORY);
        inbox.setRead(false);
        inbox.setCreatedAt(System.currentTimeMillis());
        inboxRepository.save(inbox);
        return Optional.of(article);
    }

    Article write(Human manager, Team club, int today, Random rng) {
        String league = competitionRepository.findById(club.getCompetitionId()).map(Competition::getName).orElse("the league");
        ManagerBackground background = backgroundRepository.findByManagerId(manager.getId()).orElse(null);
        PlayingCareer career = enumOr(PlayingCareer.class, background == null ? null : background.getPlayingCareer(), PlayingCareer.NONE);
        CoachingLicence licence = enumOr(CoachingLicence.class, background == null ? null : background.getCoachingLicence(), CoachingLicence.NONE);
        String birthNation = background == null ? "" : NationService.countries().stream()
                .filter(n -> n.id() == background.getBirthNationId()).map(NationService.NationInfo::name)
                .findFirst().orElse("");

        AppointmentArticle.Gap gap = AppointmentArticle.gap(manager.getManagerReputation(), club.getReputation());
        int[] finish = lastLeagueFinish(club);
        double[] tenure = tenure(club.getId(), manager.getId());

        Map<String, String> values = new HashMap<>();
        values.put("manager", manager.getName());
        values.put("club", club.getName());
        values.put("league", league);
        values.put("age", String.valueOf(manager.getAge()));
        values.put("birthNation", birthNation.isEmpty() ? "his homeland" : birthNation);
        values.put("playingCareer", career.label());
        values.put("licence", licence.label());
        values.put("lastPosition", AppointmentArticle.ordinal(finish[0]));
        values.put("trophyCount", String.valueOf(trophies(club.getId())));
        values.put("avgTenure", String.format(Locale.ROOT, "%.1f seasons", tenure[0]));
        values.put("managerCount", String.valueOf((int) tenure[1]));

        String headline = pick("HEADLINE", AppointmentArticle.headlineBucket(gap), today, rng, values)
                .orElse(manager.getName() + " takes charge at " + club.getName());
        List<String> paragraphs = new ArrayList<>();
        pick("WHO", career.name(), today, rng, values).ifPresent(paragraphs::add);
        pick("DECISION", AppointmentArticle.decisionBucket(gap), today, rng, values).ifPresent(paragraphs::add);
        pick("CONTEXT", AppointmentArticle.contextBucket(finish[0], finish[1]), today, rng, values).ifPresent(paragraphs::add);

        Set<Long> rivals = analysts.rivalsOf(club);
        // Without the licence his league needs, every pundit is one step harsher (§3).
        boolean unlicensed = licenceService != null && !licenceService.qualifies(manager.getId(), club.getId());
        for (Analyst analyst : analysts.commentatorsFor(club)) {
            AppointmentArticle.Affinity affinity = AppointmentArticle.affinity(analyst, club.getId(), rivals);
            Analyst.Temperament tone = stance(analyst, manager.getId());
            if (unlicensed && tone.ordinal() > 0) tone = Analyst.Temperament.values()[tone.ordinal() - 1];
            String bucket = AppointmentArticle.analystBucket(tone, gap, affinity);
            pick("ANALYST", bucket, today, rng, values)
                    .ifPresent(quote -> paragraphs.add(analyst.getName() + " (" + role(analyst) + "): “" + quote + "”"));
        }
        conflictParagraph(club, manager, rng).ifPresent(paragraphs::add);
        pick("TENURE", AppointmentArticle.tenureBucket(tenure[0], (int) tenure[1]), today, rng, values)
                .ifPresent(paragraphs::add);
        return new Article(headline, paragraphs);
    }

    /**
     * At a "conflict club" (ambitious director, design DESIGN_START_CARIERA §5): the press says so, publicly, so the
     * manager knows what he walks into.
     */
    Optional<String> conflictParagraph(Team club, Human manager, Random rng) {
        if (boardRepository == null) return Optional.empty();
        Optional<ClubBoard> board = boardRepository.findByTeamId(club.getId()).filter(ClubBoard::isConflict);
        if (board.isEmpty()) return Optional.empty();
        String director = humanRepository == null ? "the general director"
                : humanRepository.findById(board.get().getDirectorId()).map(Human::getName).orElse("the general director");
        String[] lines = {
                "Behind the scenes, all is not calm at " + club.getName() + ". General director " + director
                        + " has ambitions of his own, and they do not always match the chairman's. " + manager.getName()
                        + " will find himself caught in the middle.",
                "Anyone taking this job should know that " + director + ", the general director, runs his own agenda at "
                        + club.getName() + ". Whose side " + manager.getName() + " takes may matter as much as his results.",
                "At " + club.getName() + " the chairman and general director " + director + " pull in different directions."
                        + " " + manager.getName() + " steps straight into that power struggle."
        };
        return Optional.of(lines[rng.nextInt(lines.length)]);
    }

    /** His temperament, shifted by what he remembers of this manager (press conference answers, refusals). */
    Analyst.Temperament stance(Analyst analyst, long managerId) {
        if (relationService == null) return analyst.getTemperament();
        int relation = relationService.relation(RelationParty.ANALYST, analyst.getId(), RelationParty.MANAGER, managerId);
        return AnalystStance.effective(analyst.getTemperament(), relation);
    }

    private Optional<String> pick(String category, String bucket, int today, Random rng, Map<String, String> values) {
        return fragments.next(category, bucket, today, rng).map(text -> AppointmentArticle.fill(text, values));
    }

    private static String role(Analyst analyst) {
        return analyst.getCompetitionId() == 0 ? "international pundit" : "pundit";
    }

    /** {position, teams}: the club's last league finish; without history, its reputation rank in its league. */
    int[] lastLeagueFinish(Team club) {
        Optional<CompetitionHistory> last = historyRepository.findByTeamId(club.getId()).stream()
                .filter(h -> h.getCompetitionTypeId() == Competition.LEAGUE)
                .max(Comparator.comparingLong(CompetitionHistory::getSeasonNumber));
        if (last.isPresent()) {
            int teams = historyRepository.findAllByCompetitionIdAndSeasonNumber(
                    last.get().getCompetitionId(), last.get().getSeasonNumber()).size();
            return new int[]{(int) last.get().getLastPosition(), teams};
        }
        List<Team> league = teamRepository.findAllByCompetitionId(club.getCompetitionId()).stream()
                .sorted(Comparator.comparingInt(Team::getReputation).reversed().thenComparingLong(Team::getId))
                .toList();
        int rank = 1;
        for (Team t : league) {
            if (t.getId() == club.getId()) break;
            rank++;
        }
        return new int[]{Math.min(rank, Math.max(1, league.size())), league.size()};
    }

    private int trophies(long clubId) {
        return (int) historyRepository.findByTeamId(clubId).stream().filter(h -> h.getLastPosition() == 1).count();
    }

    /** {average seasons, managers}: the club's last {@value #RECENT_MANAGERS} managers before him. */
    double[] tenure(long clubId, long newManagerId) {
        Map<Long, Long> seasonsByManager = managerHistoryRepository.findAllByTeamId(clubId).stream()
                .filter(h -> h.getManagerId() != newManagerId)
                .collect(Collectors.groupingBy(ManagerHistory::getManagerId, Collectors.counting()));
        Map<Long, Integer> lastSeason = new HashMap<>();
        managerHistoryRepository.findAllByTeamId(clubId).forEach(h ->
                lastSeason.merge(h.getManagerId(), h.getSeasonNumber(), Math::max));
        List<Long> recent = seasonsByManager.keySet().stream()
                .sorted(Comparator.comparingInt((Long id) -> lastSeason.getOrDefault(id, 0)).reversed())
                .limit(RECENT_MANAGERS)
                .toList();
        if (recent.isEmpty()) return new double[]{0, 0};
        double average = recent.stream().mapToLong(seasonsByManager::get).average().orElse(0);
        return new double[]{average, recent.size()};
    }

    private static <E extends Enum<E>> E enumOr(Class<E> type, String name, E fallback) {
        if (name == null) return fallback;
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException unknown) {
            return fallback;
        }
    }
}
