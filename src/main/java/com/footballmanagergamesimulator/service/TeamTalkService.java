package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.ManagerInbox;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.ManagerInboxRepository;
import com.footballmanagergamesimulator.util.TypeNames;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class TeamTalkService {

    @Autowired
    private HumanRepository humanRepository;
    @Autowired
    private ManagerInboxRepository managerInboxRepository;

    // Track which talk phases have been used this match
    private final Map<Long, Set<String>> talkPhasesUsed = new HashMap<>();

    /**
     * Get available talk options for a given phase and match context.
     */
    public List<Map<String, Object>> getAvailableTalks(String phase, String matchContext) {
        List<Map<String, Object>> talks = new ArrayList<>();

        switch (phase) {
            case "PRE_MATCH" -> {
                talks.add(talkOption("expect_win", "I Expect a Win",
                        "High expectations. Motivates confident players, may pressure nervous ones."));
                talks.add(talkOption("show_passion", "Show Me Passion",
                        "Demand effort and intensity. Works well with determined players."));
                talks.add(talkOption("focus", "Stay Focused",
                        "Calm, tactical approach. Keeps everyone level-headed."));
                talks.add(talkOption("no_pressure", "No Pressure",
                        "Relax the squad. Good for underdogs or under-pressure squads."));
                talks.add(talkOption("underdog", "We're the Underdogs",
                        "Rally the squad against a stronger opponent. Can inspire or discourage."));
                talks.add(talkOption("believe", "I Believe in You",
                        "Show faith in the squad. Boosts confidence for low-morale players."));
            }
            case "HALF_TIME" -> {
                // Context: "winning", "drawing", "losing"
                if ("winning".equals(matchContext)) {
                    talks.add(talkOption("pleased", "Pleased with Performance",
                            "Acknowledge good work. Maintains morale."));
                    talks.add(talkOption("keep_it_up", "Keep It Up",
                            "Encourage the team to maintain their level."));
                    talks.add(talkOption("dont_get_complacent", "Don't Get Complacent",
                            "Warn against dropping concentration."));
                } else if ("losing".equals(matchContext)) {
                    talks.add(talkOption("not_good_enough", "Not Good Enough",
                            "Express disappointment. Can fire up or demoralize."));
                    talks.add(talkOption("show_character", "Show Some Character",
                            "Demand a reaction. Works with determined players."));
                    talks.add(talkOption("calm_tactical", "Calm Tactical Change",
                            "Calmly suggest adjustments. Safe, moderate effect."));
                    talks.add(talkOption("passionate_plea", "Passionate Plea",
                            "Emotionally appeal to the squad. High risk, high reward."));
                } else { // drawing
                    talks.add(talkOption("push_for_win", "Push for the Win",
                            "Encourage attacking play and taking risks."));
                    talks.add(talkOption("stay_solid", "Stay Solid",
                            "Maintain defensive organization."));
                    talks.add(talkOption("more_effort", "I Want More Effort",
                            "Demand increased work rate."));
                }
            }
            case "POST_MATCH" -> {
                if ("winning".equals(matchContext)) {
                    talks.add(talkOption("well_done", "Well Done",
                            "Praise the team. Standard positive reaction."));
                    talks.add(talkOption("delighted", "I'm Delighted",
                            "Express great satisfaction. Big morale boost."));
                    talks.add(talkOption("room_for_improvement", "Room for Improvement",
                            "Temper celebrations. Keeps players grounded."));
                } else if ("losing".equals(matchContext)) {
                    talks.add(talkOption("disappointed", "I'm Disappointed",
                            "Express dissatisfaction. Can motivate or demoralize."));
                    talks.add(talkOption("unacceptable", "That Was Unacceptable",
                            "Strong criticism. High risk."));
                    talks.add(talkOption("learn_from_it", "Let's Learn from This",
                            "Constructive approach. Safe morale effect."));
                    talks.add(talkOption("heads_up", "Keep Your Heads Up",
                            "Encourage resilience. Helps low-morale players."));
                } else {
                    talks.add(talkOption("fair_result", "Fair Result",
                            "Accept the draw. Neutral effect."));
                    talks.add(talkOption("should_have_won", "We Should Have Won",
                            "Express frustration at not winning."));
                    talks.add(talkOption("good_point", "Good Point",
                            "Positive spin on the draw."));
                }
            }
        }

        return talks;
    }

    /**
     * Give a team talk to all players.
     */
    public Map<String, Object> giveTeamTalk(long teamId, String phase, String type, String matchContext, int season) {
        Map<String, Object> response = new LinkedHashMap<>();

        // Check if this phase has already been used this match
        Set<String> used = talkPhasesUsed.computeIfAbsent(teamId, k -> new HashSet<>());
        if (used.contains(phase)) {
            response.put("success", false);
            response.put("message", phase + " team talk already delivered this match.");
            return response;
        }

        List<Human> players = humanRepository.findAllByTeamIdAndTypeId(teamId, TypeNames.PLAYER_TYPE);
        if (players.isEmpty()) {
            response.put("success", false);
            response.put("message", "No players found.");
            return response;
        }

        Random rng = new Random();
        double totalChange = 0;
        int playersAffected = 0;
        List<Map<String, Object>> playerReactions = new ArrayList<>();

        for (Human player : players) {
            if (player.isRetired()) continue;

            double change = calculateMoraleChange(type, phase, matchContext, player, rng);
            String reaction = getPlayerReaction(change);

            double newMorale = Math.max(0, Math.min(100, player.getMorale() + change));
            player.setMorale(newMorale);

            totalChange += change;
            playersAffected++;

            Map<String, Object> pr = new LinkedHashMap<>();
            pr.put("playerId", player.getId());
            pr.put("playerName", player.getName());
            pr.put("change", Math.round(change * 10.0) / 10.0);
            pr.put("reaction", reaction);
            pr.put("newMorale", Math.round(newMorale * 10.0) / 10.0);
            playerReactions.add(pr);
        }

        humanRepository.saveAll(players);
        used.add(phase);

        double avgChange = playersAffected > 0 ? totalChange / playersAffected : 0;

        response.put("success", true);
        response.put("phase", phase);
        response.put("type", type);
        response.put("averageMoraleChange", Math.round(avgChange * 10.0) / 10.0);
        response.put("playersAffected", playersAffected);
        response.put("playerReactions", playerReactions);
        response.put("message", getTalkMessage(phase, type, avgChange));

        return response;
    }

    /**
     * Give an individual talk to a specific player.
     */
    public Map<String, Object> giveIndividualTalk(long teamId, long playerId, String type, int season) {
        Map<String, Object> response = new LinkedHashMap<>();

        Human player = humanRepository.findById(playerId).orElse(null);
        if (player == null || player.getTeamId() != teamId) {
            response.put("success", false);
            response.put("message", "Player not found or not on your team.");
            return response;
        }

        Random rng = new Random();
        double change = calculateIndividualTalkChange(type, player, rng);
        String reaction = getPlayerReaction(change);

        double newMorale = Math.max(0, Math.min(100, player.getMorale() + change));
        player.setMorale(newMorale);
        humanRepository.save(player);

        response.put("success", true);
        response.put("playerId", playerId);
        response.put("playerName", player.getName());
        response.put("type", type);
        response.put("moraleChange", Math.round(change * 10.0) / 10.0);
        response.put("reaction", reaction);
        response.put("newMorale", Math.round(newMorale * 10.0) / 10.0);
        response.put("message", getIndividualTalkMessage(type, player.getName(), change));

        // Send as inbox message
        ManagerInbox inbox = new ManagerInbox();
        inbox.setTeamId(teamId);
        inbox.setSeasonNumber(season);
        inbox.setTitle("Player Talk: " + player.getName());
        inbox.setContent(player.getName() + " reacted " + reaction.toLowerCase() +
                " to your " + type.replace("_", " ") + " talk. Morale: " +
                (change >= 0 ? "+" : "") + String.format("%.1f", change));
        inbox.setCategory("player_interaction");
        inbox.setRead(false);
        inbox.setCreatedAt(System.currentTimeMillis());
        managerInboxRepository.save(inbox);

        return response;
    }

    /**
     * Get available individual talk options.
     */
    public List<Map<String, Object>> getIndividualTalkOptions() {
        List<Map<String, Object>> options = new ArrayList<>();
        options.add(talkOption("praise", "Praise Performance",
                "Praise the player for recent performances. Best for players doing well."));
        options.add(talkOption("encourage", "Encourage",
                "Encourage a struggling player. Safe option for low-morale players."));
        options.add(talkOption("demand_more", "Demand More",
                "Tell the player you expect more. Can motivate or upset."));
        options.add(talkOption("warn", "Issue Warning",
                "Warn the player about poor performances. High risk."));
        options.add(talkOption("drop_hint", "Drop Hint (Will Be Dropped)",
                "Hint that the player may lose their place. Motivates some, angers others."));
        options.add(talkOption("reassure", "Reassure (Place Is Safe)",
                "Reassure the player their position is safe. Good for anxious players."));
        return options;
    }

    /**
     * Reset talk phases for a new match.
     */
    public void resetForNewMatch(long teamId) {
        talkPhasesUsed.remove(teamId);
    }

    /**
     * Reset all teams.
     */
    public void resetAllForNewMatch() {
        talkPhasesUsed.clear();
    }

    private double calculateMoraleChange(String type, String phase, String matchContext, Human player, Random rng) {
        double currentMorale = player.getMorale();
        boolean highMorale = currentMorale > 80;
        boolean lowMorale = currentMorale < 40;

        return switch (type) {
            // === PRE_MATCH ===
            case "expect_win" -> {
                if (highMorale) yield rng.nextDouble(3, 8);
                else if (lowMorale) yield rng.nextDouble(-3, 3); // pressure
                else yield rng.nextDouble(2, 6);
            }
            case "show_passion" -> {
                yield rng.nextDouble(4, 10);
            }
            case "focus" -> {
                yield rng.nextDouble(2, 5); // safe, consistent
            }
            case "no_pressure" -> {
                if (lowMorale) yield rng.nextDouble(3, 7); // helps stressed
                else yield rng.nextDouble(1, 3); // neutral for confident
            }
            case "underdog" -> {
                yield rng.nextDouble(3, 9); // inspire vs stronger opponent
            }
            case "believe" -> {
                if (lowMorale) yield rng.nextDouble(5, 10); // big boost for struggling
                else yield rng.nextDouble(1, 4);
            }
            // === HALF_TIME - WINNING ===
            case "pleased" -> rng.nextDouble(2, 5);
            case "keep_it_up" -> rng.nextDouble(2, 6);
            case "dont_get_complacent" -> {
                if (highMorale) yield rng.nextDouble(-2, 3); // slight backfire risk
                else yield rng.nextDouble(1, 4);
            }
            // === HALF_TIME - LOSING ===
            case "not_good_enough" -> {
                if (lowMorale) yield rng.nextDouble(-5, 2); // risk demoralizing
                else yield rng.nextDouble(3, 8); // motivating
            }
            case "show_character" -> rng.nextDouble(4, 10);
            case "calm_tactical" -> rng.nextDouble(2, 5);
            case "passionate_plea" -> {
                if (rng.nextDouble() < 0.3) yield rng.nextDouble(-4, -1); // backfire
                else yield rng.nextDouble(5, 12); // big boost
            }
            // === HALF_TIME - DRAWING ===
            case "push_for_win" -> rng.nextDouble(3, 7);
            case "stay_solid" -> rng.nextDouble(1, 4);
            case "more_effort" -> rng.nextDouble(3, 8);
            // === POST_MATCH - WIN ===
            case "well_done" -> rng.nextDouble(3, 6);
            case "delighted" -> rng.nextDouble(5, 10);
            case "room_for_improvement" -> {
                if (highMorale) yield rng.nextDouble(-2, 2); // dampens mood
                else yield rng.nextDouble(1, 4);
            }
            // === POST_MATCH - LOSS ===
            case "disappointed" -> {
                if (lowMorale) yield rng.nextDouble(-5, 0);
                else yield rng.nextDouble(-2, 4);
            }
            case "unacceptable" -> {
                if (lowMorale) yield rng.nextDouble(-8, -2); // devastating
                else if (highMorale) yield rng.nextDouble(2, 6); // fires up
                else yield rng.nextDouble(-3, 4);
            }
            case "learn_from_it" -> rng.nextDouble(1, 4); // safe
            case "heads_up" -> {
                if (lowMorale) yield rng.nextDouble(4, 8);
                else yield rng.nextDouble(1, 3);
            }
            // === POST_MATCH - DRAW ===
            case "fair_result" -> rng.nextDouble(0, 3);
            case "should_have_won" -> rng.nextDouble(-2, 4);
            case "good_point" -> rng.nextDouble(2, 5);
            default -> rng.nextDouble(-1, 1);
        };
    }

    private double calculateIndividualTalkChange(String type, Human player, Random rng) {
        double currentMorale = player.getMorale();
        boolean highMorale = currentMorale > 80;
        boolean lowMorale = currentMorale < 40;

        return switch (type) {
            case "praise" -> {
                if (highMorale) yield rng.nextDouble(2, 5);
                else yield rng.nextDouble(4, 10); // bigger boost if underperforming
            }
            case "encourage" -> {
                if (lowMorale) yield rng.nextDouble(5, 12);
                else yield rng.nextDouble(2, 5);
            }
            case "demand_more" -> {
                if (lowMorale) yield rng.nextDouble(-4, 3);
                else yield rng.nextDouble(2, 8);
            }
            case "warn" -> {
                if (lowMorale) yield rng.nextDouble(-8, -2);
                else yield rng.nextDouble(-3, 5);
            }
            case "drop_hint" -> {
                if (highMorale) yield rng.nextDouble(3, 8); // motivates
                else yield rng.nextDouble(-6, 2); // can demoralize
            }
            case "reassure" -> {
                if (lowMorale) yield rng.nextDouble(5, 10);
                else yield rng.nextDouble(1, 3);
            }
            default -> 0;
        };
    }

    private String getPlayerReaction(double change) {
        if (change >= 8) return "Fired Up";
        if (change >= 4) return "Motivated";
        if (change >= 1) return "Pleased";
        if (change >= -1) return "Neutral";
        if (change >= -4) return "Unhappy";
        return "Furious";
    }

    private String getTalkMessage(String phase, String type, double avgChange) {
        String changeStr = (avgChange >= 0 ? "+" : "") + String.format("%.1f", avgChange);
        String phaseLabel = switch (phase) {
            case "PRE_MATCH" -> "Pre-match";
            case "HALF_TIME" -> "Half-time";
            case "POST_MATCH" -> "Post-match";
            default -> phase;
        };
        return phaseLabel + " talk delivered (" + type.replace("_", " ") + "). Average morale change: " + changeStr;
    }

    private String getIndividualTalkMessage(String type, String playerName, double change) {
        String reaction = getPlayerReaction(change);
        return playerName + " reacted " + reaction.toLowerCase() + " to your " +
                type.replace("_", " ") + " talk. Morale change: " +
                (change >= 0 ? "+" : "") + String.format("%.1f", change);
    }

    private Map<String, Object> talkOption(String id, String label, String description) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("label", label);
        m.put("description", description);
        return m;
    }
}
