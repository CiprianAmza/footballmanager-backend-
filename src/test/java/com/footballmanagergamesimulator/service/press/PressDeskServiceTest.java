package com.footballmanagergamesimulator.service.press;

import com.footballmanagergamesimulator.model.Analyst;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.MatchStats;
import com.footballmanagergamesimulator.model.RelationParty;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.model.TransferOffer;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.MatchStatsRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.service.RelationService;
import com.footballmanagergamesimulator.util.TypeNames;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PressDeskServiceTest {

    private static final long LEAGUE = 3;
    private static final long ORBIT = 8;
    private static final long NOVA = 9;

    private PressFragmentService fragments;
    private AnalystService analysts;
    private TeamRepository teams;
    private HumanRepository humans;
    private RelationService relations;
    private MatchStatsRepository matchStats;
    private PressDeskService desk;

    @BeforeEach
    void setUp() {
        fragments = mock(PressFragmentService.class);
        analysts = mock(AnalystService.class);
        teams = mock(TeamRepository.class);
        humans = mock(HumanRepository.class);
        relations = mock(RelationService.class);
        matchStats = mock(MatchStatsRepository.class);
        desk = new PressDeskService(fragments, analysts, teams, humans, relations, matchStats);

        Team orbit = new Team();
        orbit.setId(ORBIT);
        orbit.setName("Orbit FC");
        orbit.setCompetitionId(LEAGUE);
        when(teams.findById(ORBIT)).thenReturn(Optional.of(orbit));
        when(analysts.rivalsOf(any())).thenReturn(Set.of());
        when(relations.relation(any(), anyLong(), any(), anyLong())).thenReturn(RelationService.NEUTRAL);
        // Every fragment reads "<section>/<bucket>" followed by the values that section may use.
        when(fragments.next(anyString(), any(), anyString(), anyString(), anyInt(), any(Random.class)))
                .thenAnswer(call -> {
                    String section = call.getArgument(2);
                    String tail = PressFragmentService.TRANSFER_RUMOUR.equals(call.getArgument(0))
                            ? " {player} {buyer} {seller} {fee}"
                            : "STATS".equals(section) ? " {shots}-{opponentShots} {shotsOnTarget}-{opponentShotsOnTarget} {possession}%"
                            : " {team} {opponent} {score} {manager}";
                    return Optional.of(section + "/" + call.getArgument(3) + tail);
                });
    }

    @Test
    @DisplayName("""
            GIVEN Orbit FC lost 0-4 at home to Nova United with 40% of the ball, 8 shots to 12 and 2 on target to 6,
             and their league's match reporter Dan Popa is a tabloid critic (harshness 80) who always shouts
             (sensationalism 100), always quotes numbers (stats focus 100) and writes long (verbosity 3)
            WHEN he writes the post-match article
            THEN it is signed by him, written from tabloid fragments, the headline carries a kicker, and the body
             has the score line, two verdicts, the numbers of a side that was second best, and a critic's talking
             point; the manager's name and the spoken score (4-0) are filled in
            """)
    void loudCriticWritesLongWithNumbers() {
        Analyst reporter = reporter(5, "Dan Popa", Analyst.Beat.MATCHES, Analyst.Voice.TABLOID);
        reporter.setHarshness(80);
        reporter.setSensationalism(100);
        reporter.setStatsFocus(100);
        reporter.setVerbosity(3);
        when(analysts.reportersFor(LEAGUE, Analyst.Beat.MATCHES)).thenReturn(List.of(reporter));
        Human manager = new Human();
        manager.setId(77);
        manager.setName("Ion Marin");
        when(humans.findAllByTeamIdAndTypeId(ORBIT, TypeNames.MANAGER_TYPE)).thenReturn(List.of(manager));
        MatchStats stats = new MatchStats();
        stats.setTeam1Id(ORBIT);
        stats.setTeam2Id(NOVA);
        stats.setHomePossession(40);
        stats.setAwayPossession(60);
        stats.setHomeShots(8);
        stats.setAwayShots(12);
        stats.setHomeShotsOnTarget(2);
        stats.setAwayShotsOnTarget(6);
        when(matchStats.findByCompetitionIdAndSeasonNumberAndRoundNumberAndTeam1IdAndTeam2Id(LEAGUE, 3, 12, ORBIT, NOVA))
                .thenReturn(Optional.of(stats));

        PressDeskService.Piece piece = desk.matchReaction(defeat()).orElseThrow();

        assertEquals(5, piece.reporterId());
        assertEquals("Dan Popa", piece.reporterName());
        assertEquals("KICKER/BIG_LOSS Orbit FC Nova United 4-0 Ion Marin: HEADLINE/BIG_LOSS Orbit FC Nova United 4-0 Ion Marin",
                piece.headline());
        assertEquals(String.join("\n\n",
                "MEDIA WATCH · By Dan Popa",
                "Premier Division: Orbit FC 0-4 Nova United.",
                "VERDICT/BIG_LOSS Orbit FC Nova United 4-0 Ion Marin",
                "VERDICT/BIG_LOSS Orbit FC Nova United 4-0 Ion Marin",
                "STATS/LOSS_SECOND_BEST 8-12 2-6 40%",
                "TALKING_POINT/LOSS_CRITIC Orbit FC Nova United 4-0 Ion Marin"), piece.body());
        verify(fragments).ensureLoaded();
        verify(analysts).ensureReporters();
        verify(relations).relation(RelationParty.ANALYST, 5, RelationParty.MANAGER, 77);
        verify(fragments, never()).next(anyString(), eq("AGENCY"), anyString(), anyString(), anyInt(), any(Random.class));
    }

    @Test
    @DisplayName("""
            GIVEN Orbit FC won 2-1 and their reporter is a balanced agency man (harshness 50) who supports Orbit FC,
             never shouts, never quotes numbers and writes two paragraphs; the club has no manager on record
            WHEN he writes the post-match article
            THEN the headline has no kicker, the body is the verdict and a supportive talking point (he is one tier
             kinder to his own club), and the manager reads "the manager"
            """)
    void quietSupporterWritesShortAndKind() {
        Analyst reporter = reporter(6, "Mara Ene", Analyst.Beat.MATCHES, Analyst.Voice.AGENCY);
        reporter.setHarshness(50);
        reporter.setFavoriteTeamId(ORBIT);
        reporter.setSensationalism(0);
        reporter.setStatsFocus(0);
        reporter.setVerbosity(2);
        when(analysts.reportersFor(LEAGUE, Analyst.Beat.MATCHES)).thenReturn(List.of(reporter));
        when(humans.findAllByTeamIdAndTypeId(ORBIT, TypeNames.MANAGER_TYPE)).thenReturn(List.of());

        PressDeskService.Piece piece = desk.matchReaction(new MatchReactionArticle.Facts(
                ORBIT, "Orbit FC", NOVA, "Nova United", 2, 1, LEAGUE, "Premier Division", 3, 12, ORBIT, NOVA)).orElseThrow();

        assertEquals("HEADLINE/WIN Orbit FC Nova United 2-1 the manager", piece.headline());
        assertEquals(String.join("\n\n",
                "MEDIA WATCH · By Mara Ene",
                "Premier Division: Orbit FC 2-1 Nova United.",
                "VERDICT/WIN Orbit FC Nova United 2-1 the manager",
                "TALKING_POINT/WIN_SUPPORTIVE Orbit FC Nova United 2-1 the manager"), piece.body());
        verify(fragments, never()).next(anyString(), any(), eq("KICKER"), anyString(), anyInt(), any(Random.class));
        verify(fragments, never()).next(anyString(), any(), eq("STATS"), anyString(), anyInt(), any(Random.class));
    }

    @Test
    @DisplayName("""
            GIVEN a league without match reporters, then a reporter whose voice has no headline for the result
            WHEN the post-match article is asked for
            THEN the desk writes nothing both times, so the caller keeps its plain text
            """)
    void noReporterOrNoHeadlineMeansNoArticle() {
        when(analysts.reportersFor(LEAGUE, Analyst.Beat.MATCHES)).thenReturn(List.of());
        assertTrue(desk.matchReaction(defeat()).isEmpty());

        Analyst reporter = reporter(5, "Dan Popa", Analyst.Beat.MATCHES, Analyst.Voice.TABLOID);
        when(analysts.reportersFor(LEAGUE, Analyst.Beat.MATCHES)).thenReturn(List.of(reporter));
        when(fragments.next(anyString(), any(), eq("HEADLINE"), anyString(), anyInt(), any(Random.class)))
                .thenReturn(Optional.empty());
        assertTrue(desk.matchReaction(defeat()).isEmpty());
    }

    @Test
    @DisplayName("""
            GIVEN Orbit FC and Nova United are negotiating over Alex Prospect at a real 12,000,000, and the
             transfer reporter of Orbit FC's league is an insider of reliability 90
            WHEN he writes the rumour
            THEN it is signed by him, uses the STRONG fragments, shows credibility 95% (rarely wrong), quotes a fee
             within 5% of the real one (plus rounding) and still says neither club has confirmed the story
            """)
    void reliableInsiderGetsTheRumourNearlyRight() {
        Team buyer = new Team();
        buyer.setId(7);
        buyer.setCompetitionId(LEAGUE);
        when(teams.findById(7L)).thenReturn(Optional.of(buyer));
        Analyst reporter = reporter(12, "Radu Voss", Analyst.Beat.TRANSFERS, Analyst.Voice.INSIDER);
        reporter.setReliability(90);
        when(analysts.reportersFor(LEAGUE, Analyst.Beat.TRANSFERS)).thenReturn(List.of(reporter));
        TransferOffer offer = new TransferOffer();
        offer.setId(41);
        offer.setPlayerName("Alex Prospect");
        offer.setFromTeamId(7);
        offer.setFromTeamName("Orbit FC");
        offer.setToTeamId(8);
        offer.setToTeamName("Nova United");
        offer.setOfferAmount(12_000_000);
        offer.setSeasonNumber(4);
        offer.setStatus("negotiating");

        PressDeskService.Piece piece = desk.transferRumour(offer, 9).orElseThrow();

        assertEquals("Radu Voss", piece.reporterName());
        assertTrue(piece.headline().startsWith("HEADLINE/STRONG Alex Prospect Orbit FC Nova United €"), piece.headline());
        assertTrue(piece.body().startsWith("TRANSFER RUMOUR · By Radu Voss\nCredibility: 95% · rarely wrong\n\nBODY/STRONG "),
                piece.body());
        assertTrue(piece.body().endsWith("\n\nNeither club has publicly confirmed the story."), piece.body());
        Matcher fee = Pattern.compile("€([\\d,]+)").matcher(piece.body());
        assertTrue(fee.find());
        long quoted = Long.parseLong(fee.group(1).replace(",", ""));
        assertTrue(Math.abs(quoted - 12_000_000) <= 12_000_000 * 0.08, "quoted " + quoted);
        assertFalse(piece.body().contains("{"), piece.body());
        verify(fragments, never()).next(anyString(), any(), anyString(), eq("EARLY"), anyInt(), any(Random.class));
    }

    private static MatchReactionArticle.Facts defeat() {
        return new MatchReactionArticle.Facts(ORBIT, "Orbit FC", NOVA, "Nova United", 0, 4, LEAGUE,
                "Premier Division", 3, 12, ORBIT, NOVA);
    }

    private static Analyst reporter(long id, String name, Analyst.Beat beat, Analyst.Voice voice) {
        Analyst reporter = new Analyst();
        reporter.setId(id);
        reporter.setName(name);
        reporter.setCompetitionId(LEAGUE);
        reporter.setRole(Analyst.Role.REPORTER);
        reporter.setBeat(beat);
        reporter.setVoice(voice);
        return reporter;
    }
}
