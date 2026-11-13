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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;

/**
 * The reporters at work (design DESIGN_START_CARIERA §6.1.1, decided 2026-11-13): a league's reporter writes and
 * signs the post-match article and the transfer rumour of its clubs, from the fragments of his own voice and
 * shaped by his style parameters. Returns nothing when the world has no reporter or no fragment for the piece;
 * the caller then keeps its plain text.
 */
@Service
public class PressDeskService {

    static final String UNNAMED_MANAGER = "the manager";

    private final PressFragmentService fragments;
    private final AnalystService analysts;
    private final TeamRepository teamRepository;
    private final HumanRepository humanRepository;
    private final RelationService relationService;
    private final MatchStatsRepository matchStatsRepository;

    public PressDeskService(PressFragmentService fragments, AnalystService analysts, TeamRepository teamRepository,
                            HumanRepository humanRepository, RelationService relationService,
                            MatchStatsRepository matchStatsRepository) {
        this.fragments = fragments;
        this.analysts = analysts;
        this.teamRepository = teamRepository;
        this.humanRepository = humanRepository;
        this.relationService = relationService;
        this.matchStatsRepository = matchStatsRepository;
    }

    /** A written piece and who signed it. */
    public record Piece(String headline, String body, long reporterId, String reporterName) {
    }

    /** The post-match article on {@code facts.teamId()}, by one of its league's match reporters. */
    @Transactional
    public Optional<Piece> matchReaction(MatchReactionArticle.Facts facts) {
        Team club = teamRepository.findById(facts.teamId()).orElse(null);
        if (club == null) return Optional.empty();
        List<Analyst> reporters = reporters(club.getCompetitionId(), Analyst.Beat.MATCHES);
        if (reporters.isEmpty()) return Optional.empty();
        Analyst reporter = reporters.get(
                Math.floorMod(Objects.hash(facts.teamId(), facts.season(), facts.round()), reporters.size()));
        String voice = voiceOf(reporter);
        int today = facts.season() * 365 + facts.round();
        Random rng = new Random(Objects.hash(facts.teamId(), facts.opponentTeamId(), facts.season(), facts.round(),
                reporter.getId()));

        Human manager = humanRepository.findAllByTeamIdAndTypeId(facts.teamId(), TypeNames.MANAGER_TYPE).stream()
                .findFirst().orElse(null);
        int relation = manager == null ? RelationService.NEUTRAL
                : relationService.relation(RelationParty.ANALYST, reporter.getId(), RelationParty.MANAGER, manager.getId());
        AppointmentArticle.Affinity affinity = AppointmentArticle.affinity(reporter, club.getId(), analysts.rivalsOf(club));
        Analyst.Temperament tone = MatchReactionArticle.tone(reporter, relation, affinity);
        MatchReactionArticle.Result result = MatchReactionArticle.result(facts.teamScore(), facts.opponentScore());

        Map<String, String> values = new HashMap<>();
        values.put("team", facts.teamName());
        values.put("opponent", facts.opponentName());
        values.put("score", MatchReactionArticle.score(facts.teamScore(), facts.opponentScore()));
        values.put("competition", facts.competitionName());
        values.put("manager", manager == null || manager.getName() == null ? UNNAMED_MANAGER : manager.getName());

        Optional<MatchReactionArticle.Numbers> numbers = numbers(facts);
        boolean loud = MatchReactionArticle.happens(reporter.getSensationalism(), rng);
        boolean quotesNumbers = numbers.isPresent() && MatchReactionArticle.happens(reporter.getStatsFocus(), rng);
        numbers.ifPresent(n -> {
            values.put("possession", String.valueOf(n.possession()));
            values.put("shots", String.valueOf(n.shots()));
            values.put("shotsOnTarget", String.valueOf(n.shotsOnTarget()));
            values.put("opponentShots", String.valueOf(n.opponentShots()));
            values.put("opponentShotsOnTarget", String.valueOf(n.opponentShotsOnTarget()));
        });

        Optional<String> headline = pick(PressFragmentService.POST_MATCH, voice, MatchReactionArticle.HEADLINE,
                result.name(), today, rng, values);
        if (headline.isEmpty()) return Optional.empty();
        String kicker = !loud ? null : pick(PressFragmentService.POST_MATCH, voice, MatchReactionArticle.KICKER,
                result.name(), today, rng, values).orElse(null);

        List<String> paragraphs = new ArrayList<>();
        for (String section : MatchReactionArticle.sections(reporter.getVerbosity(), quotesNumbers)) {
            String bucket = switch (section) {
                case MatchReactionArticle.STATS -> MatchReactionArticle.statsBucket(result, numbers.orElseThrow());
                case MatchReactionArticle.TALKING_POINT -> MatchReactionArticle.talkingPointBucket(result, tone);
                default -> result.name();
            };
            pick(PressFragmentService.POST_MATCH, voice, section, bucket, today, rng, values).ifPresent(paragraphs::add);
        }
        if (paragraphs.isEmpty()) return Optional.empty();

        String body = "MEDIA WATCH · By " + reporter.getName() + "\n\n"
                + facts.competitionName() + ": " + facts.teamName() + " " + facts.teamScore() + "-"
                + facts.opponentScore() + " " + facts.opponentName() + ".\n\n"
                + String.join("\n\n", paragraphs);
        return Optional.of(new Piece(MatchReactionArticle.headline(kicker, headline.get()), body,
                reporter.getId(), reporter.getName()));
    }

    /** The rumour about a real offer, by the transfer reporter of the buying club's league. */
    @Transactional
    public Optional<Piece> transferRumour(TransferOffer offer, int round) {
        Team buyer = teamRepository.findById(offer.getFromTeamId()).orElse(null);
        if (buyer == null) return Optional.empty();
        List<Analyst> reporters = reporters(buyer.getCompetitionId(), Analyst.Beat.TRANSFERS);
        if (reporters.isEmpty()) return Optional.empty();
        Analyst reporter = reporters.get((int) Math.floorMod(offer.getId(), (long) reporters.size()));
        String voice = voiceOf(reporter);
        int today = offer.getSeasonNumber() * 365 + round;
        Random rng = new Random(Objects.hash(offer.getId(), reporter.getId()));

        boolean advanced = TransferRumourArticle.advanced(offer.getStatus());
        String bucket = TransferRumourArticle.bucket(advanced);
        int credibility = TransferRumourArticle.credibility(reporter.getReliability(), advanced, rng);
        long fee = TransferRumourArticle.reportedFee(offer.getOfferAmount(), reporter.getReliability(), rng);

        Map<String, String> values = new HashMap<>();
        values.put("player", offer.getPlayerName());
        values.put("buyer", offer.getFromTeamName());
        values.put("seller", offer.getToTeamName());
        values.put("fee", TransferRumourArticle.money(fee));

        Optional<String> headline = pick(PressFragmentService.TRANSFER_RUMOUR, voice, TransferRumourArticle.HEADLINE,
                bucket, today, rng, values);
        Optional<String> story = pick(PressFragmentService.TRANSFER_RUMOUR, voice, TransferRumourArticle.BODY,
                bucket, today, rng, values);
        if (headline.isEmpty() || story.isEmpty()) return Optional.empty();

        String body = "TRANSFER RUMOUR · By " + reporter.getName() + "\n"
                + "Credibility: " + credibility + "% · " + TransferRumourArticle.label(credibility) + "\n\n"
                + story.get() + "\n\n"
                + "Neither club has publicly confirmed the story.";
        return Optional.of(new Piece(headline.get(), body, reporter.getId(), reporter.getName()));
    }

    private List<Analyst> reporters(long competitionId, Analyst.Beat beat) {
        fragments.ensureLoaded();
        analysts.ensureReporters();
        return analysts.reportersFor(competitionId, beat);
    }

    /** The saved numbers of this match, turned to the club's side; empty when they were not kept. */
    private Optional<MatchReactionArticle.Numbers> numbers(MatchReactionArticle.Facts facts) {
        if (facts.competitionId() <= 0 || facts.homeTeamId() <= 0 || facts.awayTeamId() <= 0) return Optional.empty();
        Optional<MatchStats> saved = matchStatsRepository.findByCompetitionIdAndSeasonNumberAndRoundNumberAndTeam1IdAndTeam2Id(
                facts.competitionId(), facts.season(), facts.round(), facts.homeTeamId(), facts.awayTeamId());
        return saved.filter(stats -> stats.getHomePossession() + stats.getAwayPossession() > 0)
                .map(stats -> facts.teamId() == stats.getTeam1Id()
                ? new MatchReactionArticle.Numbers(stats.getHomePossession(), stats.getHomeShots(),
                stats.getHomeShotsOnTarget(), stats.getAwayShots(), stats.getAwayShotsOnTarget())
                : new MatchReactionArticle.Numbers(stats.getAwayPossession(), stats.getAwayShots(),
                stats.getAwayShotsOnTarget(), stats.getHomeShots(), stats.getHomeShotsOnTarget()));
    }

    private Optional<String> pick(String eventType, String voice, String category, String bucket, int today,
                                  Random rng, Map<String, String> values) {
        return fragments.next(eventType, voice, category, bucket, today, rng)
                .map(text -> AppointmentArticle.fill(text, values));
    }

    private static String voiceOf(Analyst reporter) {
        return reporter.getVoice() == null ? null : reporter.getVoice().name();
    }
}
