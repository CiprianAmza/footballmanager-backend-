package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.ClubLegend;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.InboxAudience;
import com.footballmanagergamesimulator.model.ManagerInbox;
import com.footballmanagergamesimulator.repository.ClubLegendRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.ManagerInboxRepository;
import com.footballmanagergamesimulator.repository.ScorerRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Creates contextual, original media quotes from players who represented a club in the past. */
@Service
public class FormerPlayerStatementService {

    private final ScorerRepository scorers;
    private final HumanRepository humans;
    private final ClubLegendRepository legends;
    private final ManagerInboxRepository inbox;

    public FormerPlayerStatementService(ScorerRepository scorers, HumanRepository humans,
                                        ClubLegendRepository legends, ManagerInboxRepository inbox) {
        this.scorers = scorers;
        this.humans = humans;
        this.legends = legends;
        this.inbox = inbox;
    }

    public void publishPostMatchStatement(long teamId, String teamName, long opponentTeamId,
                                          String opponentName, int teamScore, int opponentScore,
                                          String competitionName, int season, int round) {
        if (!isNewsworthy(teamId, teamScore, opponentScore, season, round)) return;

        List<ScorerRepository.LegacyRecordAggregate> history = scorers.aggregateClubLegacy(teamId);
        if (history.isEmpty()) return;
        Map<Long, Human> people = new HashMap<>();
        humans.findAllById(history.stream().map(row -> safe(row.getPlayerId())).toList())
                .forEach(player -> people.put(player.getId(), player));
        Set<Long> officialLegendIds = new HashSet<>();
        for (ClubLegend legend : legends.findAllByTeamIdOrderByInductedSeasonDescInductedAtDesc(teamId)) {
            officialLegendIds.add(legend.getPlayerId());
        }

        List<FormerVoice> voices = new ArrayList<>();
        for (ScorerRepository.LegacyRecordAggregate row : history) {
            long playerId = safe(row.getPlayerId());
            Human player = people.get(playerId);
            if (player == null || Objects.equals(player.getTeamId(), teamId) || safe(row.getAppearances()) < 10) continue;
            voices.add(new FormerVoice(player, row, officialLegendIds.contains(playerId)));
        }
        voices.sort(Comparator.comparing((FormerVoice voice) -> voice.officialLegend()).reversed()
                .thenComparing(Comparator.comparingLong((FormerVoice voice) -> safe(voice.record().getAppearances())).reversed())
                .thenComparing(voice -> voice.player().getName()));
        if (voices.isEmpty()) return;

        List<FormerVoice> preferredVoices = voices.stream().anyMatch(FormerVoice::officialLegend)
                ? voices.stream().filter(FormerVoice::officialLegend).toList()
                : voices;
        int poolSize = Math.min(preferredVoices.size(), 5);
        int voiceIndex = (int) Math.floorMod(teamId * 31L + opponentTeamId * 17L + season * 13L + round, poolSize);
        FormerVoice voice = preferredVoices.get(voiceIndex);
        long playerId = voice.player().getId();
        String deduplicationKey = "FORMER_PLAYER_MATCH:" + season + ":" + round + ":" + teamId + ":"
                + opponentTeamId + ":" + playerId;
        if (inbox.existsByTeamIdAndDeduplicationKey(teamId, deduplicationKey)) return;

        String quote = quote(voice.player().getPosition(), teamScore - opponentScore, teamScore + opponentScore,
                teamName, opponentName);
        long appearances = safe(voice.record().getAppearances());
        long goals = safe(voice.record().getGoals());
        long assists = safe(voice.record().getAssists());
        String role = voice.officialLegend() ? "Club legend" : "Former player";
        String title = voice.player().getName() + " gives verdict on " + teamName;
        String content = "FORMER PLAYER VIEW\n\n"
                + voice.player().getName() + " · " + role + " · " + appearances + " appearances, "
                + goals + " goals, " + assists + " assists\n\n"
                + "Speaking after " + teamName + "'s " + teamScore + "-" + opponentScore + " result against "
                + opponentName + " in " + competitionName + ":\n\n“" + quote + "”\n\n"
                + "The comments reflect the former player's personal view and are not an official club statement.";

        ManagerInbox message = new ManagerInbox();
        message.setTeamId(teamId);
        message.setSeasonNumber(season);
        message.setRoundNumber(round);
        message.setTitle(title);
        message.setContent(content);
        message.setCategory("MEDIA_FORMER_PLAYER");
        message.setRead(false);
        message.setCreatedAt(System.currentTimeMillis());
        message.setAudience(InboxAudience.MANAGER);
        message.setDeduplicationKey(deduplicationKey);
        inbox.save(message);
    }

    private boolean isNewsworthy(long teamId, int scored, int conceded, int season, int round) {
        int margin = Math.abs(scored - conceded);
        return margin >= 2 || scored + conceded >= 4 || Math.floorMod(teamId + season + round, 3) == 0;
    }

    private String quote(String position, int margin, int totalGoals, String teamName, String opponentName) {
        String unit = positionUnit(position);
        if (margin >= 3) {
            return "That is the authority supporters expect from " + teamName + ". The " + unit
                    + " set the tone, but the real test is carrying those standards into the next match.";
        }
        if (margin > 0) {
            return "Winning matters, but this club has always demanded control as well as courage. There were good signs against "
                    + opponentName + ", especially from the " + unit + ".";
        }
        if (margin == 0 && totalGoals >= 4) {
            return "It was entertaining, but former players look at where the team lost control. The " + unit
                    + " will know that excitement cannot replace game management.";
        }
        if (margin == 0) {
            return "A draw is not a disaster, but at " + teamName + " you are judged on whether you impose yourself. The "
                    + unit + " must be braver in the decisive moments.";
        }
        if (margin <= -3) {
            return "The badge deserves a response. Losing happens, but the standards, body language and responsibility in the "
                    + unit + " cannot disappear when the match turns difficult.";
        }
        return "I know the pressure that comes with representing " + teamName + ". The answer has to come on the pitch, and the "
                + unit + " must lead that reaction rather than wait for somebody else.";
    }

    private String positionUnit(String rawPosition) {
        String position = rawPosition == null ? "" : rawPosition.toUpperCase();
        if (position.contains("GK") || position.contains("DC") || position.contains("DL") || position.contains("DR")) {
            return "defensive unit";
        }
        if (position.contains("MC") || position.contains("DM") || position.contains("AM")) return "midfield";
        return "attack";
    }

    private long safe(Long value) { return value == null ? 0 : value; }

    private record FormerVoice(Human player, ScorerRepository.LegacyRecordAggregate record,
                               boolean officialLegend) {}
}
