package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.InboxAudience;
import com.footballmanagergamesimulator.model.ManagerHistory;
import com.footballmanagergamesimulator.model.ManagerInbox;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.ManagerHistoryRepository;
import com.footballmanagergamesimulator.repository.ManagerInboxRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Publishes tactical post-match opinions from managers who previously led the club. */
@Service
public class FormerManagerStatementService {

    private final ManagerHistoryRepository historyRepository;
    private final HumanRepository humans;
    private final ManagerInboxRepository inbox;

    public FormerManagerStatementService(ManagerHistoryRepository historyRepository,
                                         HumanRepository humans, ManagerInboxRepository inbox) {
        this.historyRepository = historyRepository;
        this.humans = humans;
        this.inbox = inbox;
    }

    public void publishPostMatchStatement(long teamId, String teamName, long opponentTeamId,
                                          String opponentName, int teamScore, int opponentScore,
                                          String competitionName, int season, int round) {
        if (!isNewsworthy(teamId, teamScore, opponentScore, season, round)) return;
        List<ManagerHistory> history = historyRepository.findAllByTeamId(teamId);
        if (history.isEmpty()) return;

        Map<Long, Human> people = new HashMap<>();
        humans.findAllById(history.stream().map(ManagerHistory::getManagerId).distinct().toList())
                .forEach(manager -> people.put(manager.getId(), manager));
        Map<Long, List<ManagerHistory>> byManager = new LinkedHashMap<>();
        for (ManagerHistory row : history) {
            byManager.computeIfAbsent(row.getManagerId(), ignored -> new ArrayList<>()).add(row);
        }

        List<FormerManagerVoice> voices = new ArrayList<>();
        for (Map.Entry<Long, List<ManagerHistory>> entry : byManager.entrySet()) {
            Human manager = people.get(entry.getKey());
            if (manager != null && Objects.equals(manager.getTeamId(), teamId)) continue;
            voices.add(summarize(entry.getKey(), manager, entry.getValue()));
        }
        voices.sort(Comparator.comparingInt(FormerManagerVoice::trophySeasons).reversed()
                .thenComparing(Comparator.comparingInt(FormerManagerVoice::games).reversed())
                .thenComparing(Comparator.comparingInt(FormerManagerVoice::lastSeason).reversed())
                .thenComparing(FormerManagerVoice::name));
        if (voices.isEmpty()) return;

        int poolSize = Math.min(voices.size(), 3);
        int index = (int) Math.floorMod(teamId * 19L + opponentTeamId * 23L + season * 11L + round, poolSize);
        FormerManagerVoice voice = voices.get(index);
        String deduplicationKey = "FORMER_MANAGER_MATCH:" + season + ":" + round + ":" + teamId + ":"
                + opponentTeamId + ":" + voice.managerId();
        if (inbox.existsByTeamIdAndDeduplicationKey(teamId, deduplicationKey)) return;

        String quote = quote(teamScore - opponentScore, teamScore + opponentScore, teamName,
                opponentName, voice.tacticalStyle());
        double winRate = voice.games() == 0 ? 0 : voice.wins() * 100.0 / voice.games();
        String tenure = voice.firstSeason() == voice.lastSeason()
                ? "Season " + voice.firstSeason()
                : "Seasons " + voice.firstSeason() + "–" + voice.lastSeason();
        String title = voice.name() + " offers tactical verdict on " + teamName;
        String content = "FORMER HEAD COACH VIEW\n\n"
                + voice.name() + " · Former " + teamName + " manager · " + tenure + "\n"
                + voice.games() + " matches · " + voice.wins() + " wins · "
                + String.format("%.1f%% win rate", winRate)
                + (voice.trophySeasons() > 0 ? " · trophies in " + voice.trophySeasons() + " season(s)" : "") + "\n\n"
                + "Speaking after the " + teamScore + "-" + opponentScore + " result against " + opponentName
                + " in " + competitionName + ":\n\n“" + quote + "”\n\n"
                + "The comments are the former coach's personal analysis and do not represent the club or its current staff.";

        ManagerInbox message = new ManagerInbox();
        message.setTeamId(teamId);
        message.setSeasonNumber(season);
        message.setRoundNumber(round);
        message.setTitle(title);
        message.setContent(content);
        message.setCategory("MEDIA_FORMER_MANAGER");
        message.setRead(false);
        message.setCreatedAt(System.currentTimeMillis());
        message.setAudience(InboxAudience.MANAGER);
        message.setDeduplicationKey(deduplicationKey);
        inbox.save(message);
    }

    private FormerManagerVoice summarize(long managerId, Human manager, List<ManagerHistory> rows) {
        rows.sort(Comparator.comparingInt(ManagerHistory::getSeasonNumber));
        String snapshotName = rows.stream().map(ManagerHistory::getManagerName)
                .filter(name -> name != null && !name.isBlank()).findFirst().orElse("Former manager");
        int trophySeasons = (int) rows.stream().filter(row -> hasTrophy(row.getTrophiesWon())).count();
        return new FormerManagerVoice(managerId,
                manager != null && manager.getName() != null ? manager.getName() : snapshotName,
                rows.get(0).getSeasonNumber(), rows.get(rows.size() - 1).getSeasonNumber(),
                rows.stream().mapToInt(ManagerHistory::getGamesPlayed).sum(),
                rows.stream().mapToInt(ManagerHistory::getWins).sum(), trophySeasons,
                manager == null ? null : manager.getTacticStyle());
    }

    private boolean hasTrophy(String value) {
        return value != null && !value.isBlank() && !"none".equalsIgnoreCase(value.trim())
                && !"-".equals(value.trim());
    }

    private boolean isNewsworthy(long teamId, int scored, int conceded, int season, int round) {
        int margin = scored - conceded;
        return margin >= 3 || margin <= -2 || scored + conceded >= 5
                || Math.floorMod(teamId + season + round, 5) == 0;
    }

    private String quote(int margin, int totalGoals, String teamName, String opponentName, String style) {
        String identity = style == null || style.isBlank()
                ? "the structure without the ball"
                : "the balance inside the " + style + " approach";
        if (margin >= 3) {
            return "The selection made sense and the distances between the units were right. What impressed me most was "
                    + identity + "; that gave " + teamName + " control rather than just goals.";
        }
        if (margin > 0) {
            return "The result is positive, but a coach will still review how the match was managed after taking the lead. "
                    + teamName + " can be more precise without losing its aggression.";
        }
        if (margin == 0 && totalGoals >= 4) {
            return "Both transitions were too open. Against " + opponentName + ", the staff will look at rest defence, spacing and "
                    + identity + " before talking about individual mistakes.";
        }
        if (margin == 0) {
            return "The team needed another mechanism to change the rhythm. Sometimes the answer is not another attacker, but better positioning and quicker circulation.";
        }
        if (margin <= -3) {
            return "After a defeat like that, the analysis must begin with the game plan and the reaction from the technical area. "
                    + teamName + " lost compactness, " + identity
                    + " collapsed, and the current staff must rebuild certainty quickly.";
        }
        return "I would not tear up the whole plan, but the staff must identify why " + identity
                + " failed under pressure. The next selection has to reward players who accept responsibility.";
    }

    private record FormerManagerVoice(long managerId, String name, int firstSeason, int lastSeason,
                                      int games, int wins, int trophySeasons, String tacticalStyle) {}
}
