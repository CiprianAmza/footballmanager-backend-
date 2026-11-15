package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.controller.TrainingController;
import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.nameGenerator.CompositeNameGenerator;
import com.footballmanagergamesimulator.repository.*;
import com.footballmanagergamesimulator.util.TypeNames;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Additive, repeatable import of the approved Veyra/Branik/Sileth world content.
 * Never renumbers or overwrites old clubs, never rebuilds the world's calendar,
 * and never creates or redraws European competitions.
 */
@Service
public class WorldExpansionService {
    @Autowired private CompetitionRepository competitions;
    @Autowired private TeamRepository teams;
    @Autowired private CompetitionTeamInfoRepository entries;
    @Autowired private TeamFacilitiesRepository facilities;
    @Autowired private StadiumRepository stadiums;
    @Autowired private TrainingScheduleRepository training;
    @Autowired private HumanRepository humans;
    @Autowired private GameCalendarRepository calendars;
    @Autowired private CompositeNameGenerator names;
    @Autowired private FaceGenerator faces;
    @Autowired private TacticService tactics;
    @Autowired private SquadGenerationService squads;
    @Autowired @Lazy private StaffService staff;
    @Autowired @Lazy private FixtureSchedulingService scheduling;
    @Autowired @Lazy private CupBracketService brackets;
    @Autowired private ScorerLeaderboardSyncService scorers;

    public record ImportResult(List<Team> addedTeams, List<Competition> addedCompetitions, int firstSeason) {
        public boolean changed() { return !addedTeams.isEmpty() || !addedCompetitions.isEmpty(); }
    }

    /** Cold bootstrap uses the same catalog as warm saves, before normal squad generation. */
    @Transactional
    public synchronized ImportResult seedStructures(int firstSeason) {
        if (firstSeason < 1) throw new IllegalArgumentException("firstSeason must be positive");
        List<Competition> knownCompetitions = new ArrayList<>(competitions.findAll());
        List<Team> knownTeams = new ArrayList<>(teams.findAll());
        List<Competition> addedCompetitions = new ArrayList<>();
        List<Team> addedTeams = new ArrayList<>();
        for (WorldExpansionCatalog.Nation nation : WorldExpansionCatalog.NATIONS) {
            // The country's top flight is the stable import identity. Club names,
            // league names and memberships may be edited later; never restore
            // those to their original seed on a subsequent start/load.
            if (knownCompetitions.stream().anyMatch(c -> c.getNationId() == nation.id()
                    && c.isTopFlight())) continue;
            Competition league = ensureCompetition(nation, Competition.LEAGUE, nation.league(),
                    knownCompetitions, addedCompetitions);
            Competition cup = ensureCompetition(nation, Competition.CUP, nation.cup(),
                    knownCompetitions, addedCompetitions);
            ensureCompetition(nation, Competition.SUPER_CUP, "Supercupa " + nation.name(),
                    knownCompetitions, addedCompetitions);
            for (WorldExpansionCatalog.Club club : nation.clubs()) {
                Team existing = knownTeams.stream().filter(t -> club.name().equalsIgnoreCase(t.getName()))
                        .findFirst().orElse(null);
                if (existing != null) {
                    if (existing.getCompetitionId() != league.getId()) {
                        throw new IllegalStateException("World expansion club name already belongs to another league: "
                                + club.name());
                    }
                    continue;
                }
                Team team = createClub(club, league.getId());
                knownTeams.add(team);
                addedTeams.add(team);
                register(team.getId(), league.getId(), firstSeason);
                register(team.getId(), cup.getId(), firstSeason);
                createManager(team, nation.id(), firstSeason);
            }
        }
        return new ImportResult(List.copyOf(addedTeams), List.copyOf(addedCompetitions), firstSeason);
    }

    /**
     * Warm saves and restored prebuilt worlds receive only missing content. When the
     * domestic season has started, the new leagues enter NEXT season; existing
     * results and calendar events are never replayed or regenerated.
     */
    @Transactional
    public synchronized ImportResult importIntoExistingWorld(Round round) {
        int season = Math.toIntExact(round.getSeason());
        int day = calendars.findBySeason(season).stream().mapToInt(GameCalendar::getCurrentDay).max().orElse(1);
        int firstSeason = day < 31 && round.getRound() <= 1 ? season : season + 1;
        ImportResult result = seedStructures(firstSeason);
        if (!result.changed()) return result;

        for (Team team : result.addedTeams()) {
            List<Human> players = squads.generateInitialSquad(team, facilities.findByTeamId(team.getId()),
                    season, 70, new Random(0x4152475553L + team.getId()));
            applyReferencePlayer(team, players);
            staff.generateInitialStaff(team.getId(), season);
            setInitialSalaryBudget(team);
            players.forEach(scorers::trackNewPlayer);
        }

        if (firstSeason == season) {
            for (Competition competition : result.addedCompetitions()) {
                if (competition.isLeague()) {
                    scheduling.getFixturesForRound(String.valueOf(competition.getId()), "1", season);
                } else if (competition.getTypeId() == Competition.CUP) {
                    // This is a brand-new cup only. Never rebuild an existing bracket.
                    brackets.generateBracket(competition.getId(), season);
                }
                scheduling.addDomesticCompetitionCalendar(competition.getId(), season);
            }
        }
        System.out.println("=== World expansion: added " + result.addedTeams().size()
                + " clubs; domestic competition begins in season " + firstSeason + " ===");
        return result;
    }

    /** Called once after the normal cold-start squad/staff loop. */
    @Transactional
    public void finishFreshSquads() {
        Set<Long> expansionLeagues = new HashSet<>();
        competitions.findAll().stream().filter(c -> c.isLeague()
                && WorldExpansionCatalog.NATION_IDS.contains(c.getNationId()))
                .forEach(c -> expansionLeagues.add(c.getId()));
        for (Team team : teams.findAll()) {
            if (!expansionLeagues.contains(team.getCompetitionId())) continue;
            applyReferencePlayer(team, humans.findAllByTeamIdAndTypeId(team.getId(), TypeNames.PLAYER_TYPE));
            setInitialSalaryBudget(team);
        }
    }

    private Competition ensureCompetition(WorldExpansionCatalog.Nation nation, long type,
                                          String name, List<Competition> known, List<Competition> added) {
        Competition existing = known.stream().filter(c -> c.getNationId() == nation.id()
                && c.getTypeId() == type && c.getTier() == 1).findFirst().orElse(null);
        if (existing != null) {
            if (!name.equals(existing.getName())) {
                throw new IllegalStateException("Nation id " + nation.id() + " is already used by " + existing.getName());
            }
            return existing;
        }
        Competition competition = new Competition();
        competition.setNationId(nation.id());
        competition.setName(name);
        competition.setTypeId(type);
        competition.setTier(1);
        competition.setPrizesId(type == Competition.CUP ? 2 : 1);
        competition = competitions.save(competition);
        known.add(competition);
        added.add(competition);
        return competition;
    }

    private Team createClub(WorldExpansionCatalog.Club club, long leagueId) {
        Team team = new Team();
        team.setName(club.name());
        team.setCompetitionId(leagueId);
        team.setColor1(club.color1());
        team.setColor2(club.color2());
        team.setBorder("35%");
        team.setReputation(club.reputation());
        team.setStrategy((long) club.strategy());
        team.setStadiumName(club.stadium());
        team.setStadiumCapacity(StadiumDesignService.initialCapacityForReputation(club.reputation()));
        team.setBoardConfidence(50);
        team.setDebt(0);
        team.setTotalFinances(club.reputation() * 10_000L);
        team.setTransferBudget(club.reputation() * 2_500L);
        team = teams.save(team);

        Stadium stadium = new Stadium();
        stadium.setTeamId(team.getId());
        stadium.setStadiumName(team.getStadiumName());
        stadium.setCapacity(team.getStadiumCapacity());
        int level = club.reputation() >= 7000 ? 3 : club.reputation() >= 5500 ? 2 : 1;
        stadium.setVipBoxesLevel(level);
        stadium.setCateringLevel(level);
        stadium.setFanShopLevel(level);
        stadium.setFastFoodLevel(level);
        stadium.setHeadquartersLevel(level + 1);
        stadium.setTrainingPitchLevel(level + 1);
        stadium.setParkingLevel(level);
        stadium = stadiums.save(stadium);
        team.setStadiumId(stadium.getId());
        teams.save(team);

        TeamFacilities facility = new TeamFacilities();
        facility.setTeamId(team.getId());
        facility.setYouthAcademyLevel(club.academy());
        facility.setYouthTrainingLevel(club.youthTraining());
        facility.setSeniorTrainingLevel(club.seniorTraining());
        facility.setScoutingLevel((club.academy() + club.youthTraining()) / 2);
        facilities.save(facility);
        training.saveAll(TrainingController.buildDefaultSchedule(team.getId()));
        return team;
    }

    private void register(long teamId, long competitionId, int season) {
        CompetitionTeamInfo entry = new CompetitionTeamInfo();
        entry.setTeamId(teamId);
        entry.setCompetitionId(competitionId);
        entry.setSeasonNumber(season);
        entry.setRound(1);
        entries.save(entry);
    }

    private void createManager(Team team, long nationId, int season) {
        Random random = new Random(0x4D414E41474552L + team.getId());
        Human manager = new Human();
        manager.setName(names.generateNameForNation(nationId));
        manager.setTeamId(team.getId());
        manager.setTypeId(TypeNames.MANAGER_TYPE);
        manager.setPosition("Manager");
        manager.setAge(35 + random.nextInt(20));
        manager.setSeasonCreated(season);
        manager.setManagerReputation(ManagerReputation.startingFor(team.getReputation()));
        manager.setMorale(70);
        manager.setFitness(100);
        manager.setContractEndSeason(season + 3);
        double ability = 40 + Math.min(1, team.getReputation() / 10000.0) * 40;
        manager.setOffensiveAbility(Math.max(1, Math.min(100, ability + random.nextDouble() * 30 - 15)));
        manager.setDefensiveAbility(Math.max(1, Math.min(100, ability + random.nextDouble() * 30 - 15)));
        String[] kit = tactics.buildManagerTacticKit(0, random);
        manager.setTacticStyle(kit[0]);
        manager.setKnownTactics(kit[1]);
        manager = humans.save(manager);
        faces.assignFace(manager, nationId);
        humans.save(manager);
    }

    private void applyReferencePlayer(Team team, List<Human> players) {
        WorldExpansionCatalog.REFERENCE_PLAYERS.stream().filter(r -> r.club().equals(team.getName()))
                .findFirst().ifPresent(reference -> {
                    Human player = players.stream().filter(p -> reference.position().equals(p.getPosition()))
                            .findFirst().orElseThrow(() -> new IllegalStateException("Missing squad position "
                                    + reference.position() + " at " + team.getName()));
                    player.setName(reference.name());
                    player.setSkinTone(reference.skin());
                    player.setHairStyle(reference.hairStyle());
                    player.setHairColor(reference.hairColor());
                    player.setEyeColor(reference.eyeColor());
                    player.setFaceShape(reference.head());
                    player.setBaseFaceId(reference.features());
                    player.setBrowShape(reference.brows());
                    player.setEyeShape(reference.eyes());
                    player.setNoseShape(reference.nose());
                    player.setMouthShape(reference.mouth());
                    humans.save(player);
                });
    }

    private void setInitialSalaryBudget(Team team) {
        long wages = humans.findAllByTeamId(team.getId()).stream()
                .filter(h -> !h.isRetired()).mapToLong(Human::getWage).sum();
        team.setSalaryBudget(Math.max(1L, Math.round(wages * 1.15)));
        teams.save(team);
    }
}
