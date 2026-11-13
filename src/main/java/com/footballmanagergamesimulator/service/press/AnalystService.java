package com.footballmanagergamesimulator.service.press;

import com.footballmanagergamesimulator.model.Analyst;
import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.nameGenerator.CompositeNameGenerator;
import com.footballmanagergamesimulator.repository.AnalystRepository;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.service.NationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * The world's pundits (design DESIGN_START_CARIERA §6.1, decided 2026-11-12): three per league (one critical, one
 * balanced, one supportive, named after the league's nation) and two international ones who comment on the
 * biggest clubs. Each supports a club with probability {@link #FAVORITE_CHANCE}.
 */
@Service
public class AnalystService {

    static final double FAVORITE_CHANCE = 0.5;
    static final int INTERNATIONAL_COUNT = 2;

    private final AnalystRepository analystRepository;
    private final CompetitionRepository competitionRepository;
    private final TeamRepository teamRepository;
    private final CompositeNameGenerator nameGenerator;
    private final Random rng = new Random();

    public AnalystService(AnalystRepository analystRepository, CompetitionRepository competitionRepository,
                          TeamRepository teamRepository, CompositeNameGenerator nameGenerator) {
        this.analystRepository = analystRepository;
        this.competitionRepository = competitionRepository;
        this.teamRepository = teamRepository;
        this.nameGenerator = nameGenerator;
    }

    /** Creates the pundits once per world. Returns how many were created. */
    @Transactional
    public int ensureAnalysts() {
        if (analystRepository.count() > 0) return 0;
        List<Analyst> created = new ArrayList<>();
        List<Competition> leagues = competitionRepository.findAll().stream()
                .filter(Competition::isLeague)
                .sorted(Comparator.comparingLong(Competition::getId))
                .toList();
        for (Competition league : leagues) {
            List<Team> clubs = teamRepository.findAllByCompetitionId(league.getId());
            for (Analyst.Temperament temperament : Analyst.Temperament.values()) {
                created.add(analyst(league.getId(), league.getNationId(), temperament, clubs));
            }
        }
        List<Team> bigClubs = teamRepository.findAll().stream()
                .filter(t -> t.getReputation() >= AppointmentArticle.INTERNATIONAL_CLUB_REPUTATION).toList();
        List<Analyst.Temperament> tempers = new ArrayList<>(List.of(Analyst.Temperament.values()));
        Collections.shuffle(tempers, rng);
        List<NationService.NationInfo> nations = NationService.countries();
        for (int i = 0; i < INTERNATIONAL_COUNT; i++) {
            long nation = nations.isEmpty() ? 0 : nations.get(rng.nextInt(nations.size())).id();
            created.add(analyst(0, nation, tempers.get(i % tempers.size()), bigClubs));
        }
        analystRepository.saveAll(created);
        return created.size();
    }

    private Analyst analyst(long competitionId, long nationId, Analyst.Temperament temperament, List<Team> clubs) {
        Analyst analyst = new Analyst();
        analyst.setName(nameGenerator.generateNameForNation(nationId));
        analyst.setCompetitionId(competitionId);
        analyst.setNationId(nationId);
        analyst.setTemperament(temperament);
        if (!clubs.isEmpty() && rng.nextDouble() < FAVORITE_CHANCE) {
            analyst.setFavoriteTeamId(clubs.get(rng.nextInt(clubs.size())).getId());
        }
        return analyst;
    }

    public List<Analyst> all() {
        return analystRepository.findAll();
    }

    /** Who comments on an appointment at this club: its league's pundits, plus the international ones for a big club. */
    public List<Analyst> commentatorsFor(Team club) {
        List<Analyst> commentators = new ArrayList<>(analystRepository.findAllByCompetitionId(club.getCompetitionId()));
        commentators.sort(Comparator.comparingLong(Analyst::getId));
        if (club.getReputation() >= AppointmentArticle.INTERNATIONAL_CLUB_REPUTATION) {
            analystRepository.findAllByCompetitionId(0).stream()
                    .sorted(Comparator.comparingLong(Analyst::getId))
                    .forEach(commentators::add);
        }
        return commentators;
    }

    /** The club's rivals: when it is among the top {@value AppointmentArticle#RIVAL_TOP} of its league, the others there. */
    public Set<Long> rivalsOf(Team club) {
        List<Long> top = teamRepository.findAllByCompetitionId(club.getCompetitionId()).stream()
                .sorted(Comparator.comparingInt(Team::getReputation).reversed().thenComparingLong(Team::getId))
                .limit(AppointmentArticle.RIVAL_TOP)
                .map(Team::getId)
                .toList();
        if (!top.contains(club.getId())) return Set.of();
        Set<Long> rivals = new HashSet<>(top);
        rivals.remove(club.getId());
        return rivals;
    }
}
