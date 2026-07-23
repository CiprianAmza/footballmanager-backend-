package com.footballmanagergamesimulator.dynamics;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 1 — persistent model. Validates entity mapping, repository finders and
 * the persistent monthly-uniqueness constraints that back the Team Meeting and
 * Player Conversation cooldowns.
 */
@DataJpaTest
class DynamicsModelPersistenceTest {

    @Autowired
    private TeamMeetingRepository teamMeetingRepository;
    @Autowired
    private TeamMeetingReactionRepository reactionRepository;
    @Autowired
    private PlayerConversationRepository conversationRepository;
    @Autowired
    private DynamicsPromiseRepository promiseRepository;

    // ---- Team Meeting -------------------------------------------------------

    @Test
    void persistsAndReadsBackATeamMeeting() {
        TeamMeeting saved = teamMeetingRepository.save(meeting(7L, 2, 10));

        assertTrue(saved.getId() > 0);
        Optional<TeamMeeting> found =
                teamMeetingRepository.findByTeamIdAndSeasonAndMonthIndex(7L, 2, 10);
        assertTrue(found.isPresent());
        assertEquals(MeetingContext.TITLE_RACE, found.get().getContext());
        assertTrue(teamMeetingRepository.existsByTeamIdAndSeasonAndMonthIndex(7L, 2, 10));
        assertFalse(teamMeetingRepository.existsByTeamIdAndSeasonAndMonthIndex(7L, 2, 11));
    }

    @Test
    void enforcesOneTeamMeetingPerTeamSeasonMonth() {
        teamMeetingRepository.saveAndFlush(meeting(7L, 2, 10));

        // Same team/season/month → unique key violation.
        assertThrows(Exception.class,
                () -> teamMeetingRepository.saveAndFlush(meeting(7L, 2, 10)));
    }

    @Test
    void allowsSameMonthForDifferentTeamsAndOtherMonths() {
        teamMeetingRepository.saveAndFlush(meeting(7L, 2, 10));
        // Different team, same month — allowed.
        teamMeetingRepository.saveAndFlush(meeting(8L, 2, 10));
        // Same team, different month — allowed.
        teamMeetingRepository.saveAndFlush(meeting(7L, 2, 11));

        assertEquals(2, teamMeetingRepository.findByTeamIdOrderBySeasonDescMonthIndexDesc(7L).size());
    }

    @Test
    void persistsMeetingReactions() {
        TeamMeeting saved = teamMeetingRepository.save(meeting(7L, 2, 10));
        reactionRepository.save(reaction(saved.getId(), 7L, 100L, HierarchyTier.LEADER,
                DynamicsReaction.FIRED_UP));
        reactionRepository.save(reaction(saved.getId(), 7L, 101L, HierarchyTier.OTHER,
                DynamicsReaction.UNHAPPY));

        assertEquals(2, reactionRepository.findByMeetingId(saved.getId()).size());
        assertEquals(2, reactionRepository.findByTeamId(7L).size());
    }

    // ---- Player Conversation ------------------------------------------------

    @Test
    void enforcesOneConversationPerPlayerSeasonMonth() {
        conversationRepository.saveAndFlush(conversation(7L, 100L, 2, 10));

        assertThrows(Exception.class,
                () -> conversationRepository.saveAndFlush(conversation(7L, 100L, 2, 10)));
    }

    @Test
    void allowsConversationsForDifferentPlayersOrMonths() {
        conversationRepository.saveAndFlush(conversation(7L, 100L, 2, 10));
        conversationRepository.saveAndFlush(conversation(7L, 101L, 2, 10)); // other player
        conversationRepository.saveAndFlush(conversation(7L, 100L, 2, 11)); // other month

        assertEquals(2, conversationRepository.findByPlayerIdOrderBySeasonDescMonthIndexDesc(100L).size());
        assertEquals(3, conversationRepository.findByTeamIdOrderBySeasonDescMonthIndexDesc(7L).size());
    }

    // ---- Promises -----------------------------------------------------------

    @Test
    void persistsAndFiltersPromisesByStatus() {
        promiseRepository.save(promise(7L, 100L, PromiseType.PLAYING_TIME, PromiseStatus.OPEN));
        promiseRepository.save(promise(7L, 100L, PromiseType.NEW_CONTRACT, PromiseStatus.KEPT));
        promiseRepository.save(promise(7L, 101L, PromiseType.CAPTAINCY, PromiseStatus.OPEN));

        assertEquals(3, promiseRepository.findByTeamId(7L).size());
        assertEquals(2, promiseRepository.findByTeamIdAndStatus(7L, PromiseStatus.OPEN).size());
        assertEquals(1, promiseRepository.findByPlayerIdAndStatus(100L, PromiseStatus.KEPT).size());

        DynamicsPromise open = promiseRepository
                .findByPlayerIdAndStatus(101L, PromiseStatus.OPEN).get(0);
        assertNotNull(open.getType());
        // resolved timestamp is nullable until the promise is closed.
        org.junit.jupiter.api.Assertions.assertNull(open.getResolvedAtEpochMillis());
    }

    // ---- factories ----------------------------------------------------------

    private static TeamMeeting meeting(long teamId, int season, int month) {
        TeamMeeting m = new TeamMeeting();
        m.setTeamId(teamId);
        m.setSeason(season);
        m.setMonthIndex(month);
        m.setDay(90);
        m.setContext(MeetingContext.TITLE_RACE);
        m.setTone(DynamicsTone.PASSIONATE);
        m.setManagerReputation(60.0);
        m.setParticipantCount(24);
        m.setAverageMoraleDelta(1.5);
        m.setSummary("Title-race rallying meeting.");
        m.setCreatedAtEpochMillis(1000L);
        return m;
    }

    private static TeamMeetingReaction reaction(long meetingId, long teamId, long playerId,
                                                HierarchyTier tier, DynamicsReaction reaction) {
        TeamMeetingReaction r = new TeamMeetingReaction();
        r.setMeetingId(meetingId);
        r.setTeamId(teamId);
        r.setPlayerId(playerId);
        r.setPlayerName("Player " + playerId);
        r.setTier(tier);
        r.setReaction(reaction);
        r.setMoraleBefore(70.0);
        r.setMoraleDelta(reaction == DynamicsReaction.FIRED_UP ? 3.0 : -2.0);
        r.setReason("test");
        return r;
    }

    private static PlayerConversation conversation(long teamId, long playerId, int season, int month) {
        PlayerConversation c = new PlayerConversation();
        c.setTeamId(teamId);
        c.setPlayerId(playerId);
        c.setPlayerName("Player " + playerId);
        c.setSeason(season);
        c.setMonthIndex(month);
        c.setDay(90);
        c.setTopic(ConversationTopic.PLAYING_TIME);
        c.setTone(DynamicsTone.CALM);
        c.setManagerReputation(60.0);
        c.setReaction(DynamicsReaction.PLEASED);
        c.setMoraleBefore(70.0);
        c.setMoraleDelta(2.0);
        c.setPlayerResponse("Understood, boss.");
        c.setSummary("Discussed playing time.");
        c.setCreatedAtEpochMillis(1000L);
        return c;
    }

    private static DynamicsPromise promise(long teamId, long playerId,
                                           PromiseType type, PromiseStatus status) {
        DynamicsPromise p = new DynamicsPromise();
        p.setTeamId(teamId);
        p.setPlayerId(playerId);
        p.setPlayerName("Player " + playerId);
        p.setSeason(2);
        p.setMonthIndex(10);
        p.setCreatedDay(90);
        p.setSource(PromiseSource.PLAYER_CONVERSATION);
        p.setSourceId(1L);
        p.setType(type);
        p.setStatus(status);
        p.setDescription("test promise");
        p.setDueSeason(2);
        p.setDueDay(180);
        p.setCreatedAtEpochMillis(1000L);
        p.setResolvedAtEpochMillis(status == PromiseStatus.OPEN ? null : 2000L);
        return p;
    }
}
