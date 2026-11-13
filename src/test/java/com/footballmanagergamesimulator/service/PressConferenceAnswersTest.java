package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.CompetitionTeamInfoMatch;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.ManagerInbox;
import com.footballmanagergamesimulator.model.PressConference;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoMatchRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.ManagerInboxRepository;
import com.footballmanagergamesimulator.repository.PressConferenceRepository;
import com.footballmanagergamesimulator.util.TypeNames;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PressConferenceAnswersTest {

    private static final long TEAM = 7;
    private static final long RIVAL = 8;
    private static final long IDLE_HUMAN = 9;
    private static final long LEAGUE = 3;
    private static final int SEASON = 2;
    private static final int ROUND = 5;

    private final PressConferenceService press = new PressConferenceService();
    /** The press_conference table. */
    private final List<PressConference> table = new ArrayList<>();
    private final List<CompetitionTeamInfoMatch> fixtures = new ArrayList<>();
    private final Human manager = new Human();
    private final Human player = new Human();

    @BeforeEach
    void wire() {
        press.pressConferenceRepository = mock(PressConferenceRepository.class);
        press.humanRepository = mock(HumanRepository.class);
        press.managerInboxRepository = mock(ManagerInboxRepository.class);
        press.competitionTeamInfoMatchRepository = mock(CompetitionTeamInfoMatchRepository.class);

        when(press.pressConferenceRepository.findAllByTeamIdAndSeasonNumber(anyLong(), anyInt())).thenAnswer(call ->
                table.stream().filter(pc -> pc.getTeamId() == (long) call.getArgument(0)
                        && pc.getSeasonNumber() == (int) call.getArgument(1)).toList());
        when(press.pressConferenceRepository.save(any(PressConference.class))).thenAnswer(call -> {
            PressConference pc = call.getArgument(0);
            if (table.stream().noneMatch(row -> row == pc)) {
                pc.setId(table.size() + 1);
                table.add(pc);
            }
            return pc;
        });
        when(press.pressConferenceRepository.findById(anyLong())).thenAnswer(call ->
                table.stream().filter(pc -> pc.getId() == (long) call.getArgument(0)).findFirst());
        when(press.competitionTeamInfoMatchRepository.findAllByCompetitionIdAndRoundAndSeasonNumber(LEAGUE, ROUND, "2"))
                .thenReturn(fixtures);

        manager.setId(70);
        manager.setTypeId(TypeNames.MANAGER_TYPE);
        manager.setManagerReputation(1_000);
        player.setId(71);
        player.setMorale(50);
        when(press.humanRepository.findAllByTeamIdAndTypeId(TEAM, TypeNames.MANAGER_TYPE)).thenReturn(List.of(manager));
        when(press.humanRepository.findAllByTeamIdAndTypeId(TEAM, TypeNames.PLAYER_TYPE)).thenReturn(List.of(player));

        fixtures.add(fixture(TEAM, RIVAL));
    }

    @Test
    @DisplayName("""
            GIVEN two human teams playing round 5 against each other, and a third human team with no match that round
            WHEN the round's pre-match conferences are created
            THEN each of the two gets its own, the third none; an older conference left unanswered can no longer be
             answered (EXPIRED)
            """)
    void everyHumanTeamThatPlaysGetsItsOwnConference() {
        PressConference older = press.generatePreMatchPressConference(TEAM, LEAGUE, ROUND - 1, 0, SEASON);

        List<PressConference> conferences = press.generatePreMatchPressConferences(
                List.of(TEAM, RIVAL, IDLE_HUMAN), LEAGUE, ROUND, 0, SEASON);

        assertEquals(List.of(TEAM, RIVAL), conferences.stream().map(PressConference::getTeamId).toList());
        assertTrue(conferences.stream().allMatch(pc -> pc.getCompetitionId() == LEAGUE && pc.getMatchDay() == ROUND));
        assertEquals(PressConferenceService.MISSED, older.getResponseChosen());
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> press.respondToPressConference(older.getId(), "cautious"));
        assertEquals("EXPIRED", refused.getMessage());
    }

    @Test
    @DisplayName("""
            GIVEN my pre-match conference
            WHEN I answer it aggressively, then try to answer it again
            THEN the squad morale moves once (+3), the bet is +2 if we win and -5 if we lose but nothing is won yet;
             the second answer is refused (ALREADY_ANSWERED)
            """)
    void aConferenceIsAnsweredOnceAndTheBetWaitsForTheMatch() {
        PressConference conference = press.generatePreMatchPressConference(TEAM, LEAGUE, ROUND, 0, SEASON);

        Map<String, Object> answer = press.respondToPressConference(conference.getId(), "aggressive");

        assertEquals(53, player.getMorale());
        assertEquals(2, answer.get("reputationIfWin"));
        assertEquals(-5, answer.get("reputationIfLose"));
        assertEquals(0, conference.getReputationEffect());
        assertEquals(1_000, manager.getManagerReputation());
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> press.respondToPressConference(conference.getId(), "confident"));
        assertEquals("ALREADY_ANSWERED", refused.getMessage());
        assertEquals(53, player.getMorale());
    }

    @Test
    @DisplayName("""
            GIVEN a post-match conference (the match is already over)
            WHEN I answer it confidently
            THEN nothing is staked on a result
            """)
    void aPostMatchAnswerStakesNothing() {
        PressConference conference = press.generatePostMatchPressConference(TEAM, LEAGUE, ROUND, SEASON, 2, 0);

        Map<String, Object> answer = press.respondToPressConference(conference.getId(), "confident");

        assertEquals(0, answer.get("reputationIfWin"));
        assertEquals(0, answer.get("reputationIfLose"));
    }

    @Test
    @DisplayName("""
            GIVEN an aggressive pre-match answer (manager reputation 1,000)
            WHEN we win 2-1, and the result is reported a second time
            THEN the manager gains 2 once (1,002), the conference keeps +2, and the media write about it once
            """)
    void aWinPaysTheBetOnce() {
        PressConference conference = press.generatePreMatchPressConference(TEAM, LEAGUE, ROUND, 0, SEASON);
        press.respondToPressConference(conference.getId(), "aggressive");
        clearInvocations(press.managerInboxRepository);

        assertEquals(2, press.settleReputationBet(TEAM, LEAGUE, ROUND, SEASON, 2, 1));
        assertEquals(0, press.settleReputationBet(TEAM, LEAGUE, ROUND, SEASON, 2, 1));

        assertEquals(1_002, manager.getManagerReputation());
        assertEquals(2, conference.getReputationEffect());
        ArgumentCaptor<ManagerInbox> story = ArgumentCaptor.forClass(ManagerInbox.class);
        verify(press.managerInboxRepository, times(1)).save(story.capture());
        assertEquals("MEDIA_REACTION", story.getValue().getCategory());
        assertTrue(story.getValue().getContent().contains("+2"));
    }

    @Test
    @DisplayName("""
            GIVEN a confident pre-match answer (manager reputation 1,000)
            WHEN we lose 0-1, or (another time) draw 1-1
            THEN the defeat costs 3 (997); the draw costs nothing
            """)
    void aDefeatCostsTheBetAndADrawNothing() {
        PressConference lost = press.generatePreMatchPressConference(TEAM, LEAGUE, ROUND, 0, SEASON);
        press.respondToPressConference(lost.getId(), "confident");
        assertEquals(-3, press.settleReputationBet(TEAM, LEAGUE, ROUND, SEASON, 0, 1));
        assertEquals(997, manager.getManagerReputation());

        PressConference drawn = press.generatePreMatchPressConference(TEAM, LEAGUE, ROUND + 1, 0, SEASON);
        press.respondToPressConference(drawn.getId(), "confident");
        assertEquals(0, press.settleReputationBet(TEAM, LEAGUE, ROUND + 1, SEASON, 1, 1));
        assertEquals(997, manager.getManagerReputation());
        assertEquals(0, drawn.getReputationEffect());
    }

    @Test
    @DisplayName("""
            GIVEN my pre-match conference for a match not yet played
            WHEN I ask for my pending conference before answering, after answering, and (unanswered) after the match
            THEN it is returned only while it is unanswered and its match still has to be played
            """)
    void theConferenceIsPendingOnlyBeforeTheMatch() {
        PressConference conference = press.generatePreMatchPressConference(TEAM, LEAGUE, ROUND, 0, SEASON);
        assertEquals(conference, press.pendingPreMatch(TEAM, SEASON));
        assertNull(press.pendingPreMatch(RIVAL, SEASON));

        fixtures.get(0).setTeam1Score(1);
        fixtures.get(0).setTeam2Score(0);
        assertNull(press.pendingPreMatch(TEAM, SEASON));

        fixtures.get(0).setTeam1Score(-1);
        press.respondToPressConference(conference.getId(), "cautious");
        assertNull(press.pendingPreMatch(TEAM, SEASON));
    }

    private static CompetitionTeamInfoMatch fixture(long home, long away) {
        CompetitionTeamInfoMatch match = new CompetitionTeamInfoMatch();
        match.setTeam1Id(home);
        match.setTeam2Id(away);
        match.setCompetitionId(LEAGUE);
        match.setRound(ROUND);
        match.setSeasonNumber(String.valueOf(SEASON));
        return match;
    }
}
