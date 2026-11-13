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
 * The world's press (design DESIGN_START_CARIERA §6.1, decided 2026-11-12; reporters and style §6.1.1, decided
 * 2026-11-13).
 * <ul>
 *   <li>Pundits: three per league (one critical, one balanced, one supportive, named after the league's nation)
 *       and two international ones who comment on the biggest clubs.</li>
 *   <li>Reporters: per league, {@link #MATCH_REPORTERS} on matches and {@link #TRANSFER_REPORTERS} on transfers,
 *       each with a different voice.</li>
 * </ul>
 * Each supports a club with probability {@link #FAVORITE_CHANCE}.
 */
@Service
public class AnalystService {

    static final double FAVORITE_CHANCE = 0.5;
    static final int INTERNATIONAL_COUNT = 2;
    static final int MATCH_REPORTERS = 2;
    static final int TRANSFER_REPORTERS = 1;

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
        for (Competition league : leagues()) {
            List<Team> clubs = teamRepository.findAllByCompetitionId(league.getId());
            for (Analyst.Temperament temperament : Analyst.Temperament.values()) {
                created.add(pundit(league.getId(), league.getNationId(), temperament, clubs));
            }
        }
        List<Team> bigClubs = teamRepository.findAll().stream()
                .filter(t -> t.getReputation() >= AppointmentArticle.INTERNATIONAL_CLUB_REPUTATION).toList();
        List<Analyst.Temperament> tempers = new ArrayList<>(List.of(Analyst.Temperament.values()));
        Collections.shuffle(tempers, rng);
        List<NationService.NationInfo> nations = NationService.countries();
        for (int i = 0; i < INTERNATIONAL_COUNT; i++) {
            long nation = nations.isEmpty() ? 0 : nations.get(rng.nextInt(nations.size())).id();
            created.add(pundit(0, nation, tempers.get(i % tempers.size()), bigClubs));
        }
        analystRepository.saveAll(created);
        return created.size();
    }

    /**
     * Makes the press room complete: the pundits, a style for every journalist that has none yet (worlds created
     * before the style parameters) and each league's reporters. Returns how many reporters were created.
     */
    @Transactional
    public int ensureReporters() {
        ensureAnalysts();
        if (analystRepository.existsByVoiceIsNull()) {
            List<Analyst> unstyled = analystRepository.findAllByVoiceIsNull();
            for (Analyst analyst : unstyled) {
                if (analyst.getRole() == null) analyst.setRole(Analyst.Role.PUNDIT);
                AnalystStyle.apply(analyst, randomVoice(), rng);
            }
            analystRepository.saveAll(unstyled);
        }
        if (analystRepository.existsByRole(Analyst.Role.REPORTER)) return 0;
        List<Analyst> created = new ArrayList<>();
        for (Competition league : leagues()) {
            List<Team> clubs = teamRepository.findAllByCompetitionId(league.getId());
            List<Analyst.Voice> voices = new ArrayList<>(List.of(Analyst.Voice.values()));
            Collections.shuffle(voices, rng);
            int next = 0;
            for (int i = 0; i < MATCH_REPORTERS; i++) {
                created.add(reporter(league, Analyst.Beat.MATCHES, voices.get(next++ % voices.size()), clubs));
            }
            for (int i = 0; i < TRANSFER_REPORTERS; i++) {
                created.add(reporter(league, Analyst.Beat.TRANSFERS, voices.get(next++ % voices.size()), clubs));
            }
        }
        analystRepository.saveAll(created);
        return created.size();
    }

    private List<Competition> leagues() {
        return competitionRepository.findAll().stream()
                .filter(Competition::isLeague)
                .sorted(Comparator.comparingLong(Competition::getId))
                .toList();
    }

    private Analyst pundit(long competitionId, long nationId, Analyst.Temperament temperament, List<Team> clubs) {
        Analyst analyst = person(competitionId, nationId, clubs);
        analyst.setRole(Analyst.Role.PUNDIT);
        analyst.setTemperament(temperament);
        AnalystStyle.apply(analyst, randomVoice(), rng);
        return analyst;
    }

    private Analyst reporter(Competition league, Analyst.Beat beat, Analyst.Voice voice, List<Team> clubs) {
        Analyst analyst = person(league.getId(), league.getNationId(), clubs);
        analyst.setRole(Analyst.Role.REPORTER);
        analyst.setBeat(beat);
        Analyst.Temperament[] tempers = Analyst.Temperament.values();
        analyst.setTemperament(tempers[rng.nextInt(tempers.length)]);
        AnalystStyle.apply(analyst, voice, rng);
        return analyst;
    }

    private Analyst person(long competitionId, long nationId, List<Team> clubs) {
        Analyst analyst = new Analyst();
        analyst.setName(nameGenerator.generateNameForNation(nationId));
        analyst.setCompetitionId(competitionId);
        analyst.setNationId(nationId);
        if (!clubs.isEmpty() && rng.nextDouble() < FAVORITE_CHANCE) {
            analyst.setFavoriteTeamId(clubs.get(rng.nextInt(clubs.size())).getId());
        }
        return analyst;
    }

    private Analyst.Voice randomVoice() {
        Analyst.Voice[] voices = Analyst.Voice.values();
        return voices[rng.nextInt(voices.length)];
    }

    public List<Analyst> all() {
        return analystRepository.findAll();
    }

    /** Who comments on an appointment at this club: its league's pundits, plus the international ones for a big club. */
    public List<Analyst> commentatorsFor(Team club) {
        List<Analyst> commentators = new ArrayList<>(pundits(club.getCompetitionId()));
        if (club.getReputation() >= AppointmentArticle.INTERNATIONAL_CLUB_REPUTATION) {
            commentators.addAll(pundits(0));
        }
        return commentators;
    }

    private List<Analyst> pundits(long competitionId) {
        return analystRepository.findAllByCompetitionId(competitionId).stream()
                .filter(a -> !a.isReporter())
                .sorted(Comparator.comparingLong(Analyst::getId))
                .toList();
    }

    /** A league's reporters on one beat, oldest first. */
    public List<Analyst> reportersFor(long competitionId, Analyst.Beat beat) {
        return analystRepository.findAllByCompetitionIdAndRoleAndBeat(competitionId, Analyst.Role.REPORTER, beat).stream()
                .sorted(Comparator.comparingLong(Analyst::getId))
                .toList();
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
