package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.InboxAudience;
import com.footballmanagergamesimulator.model.ManagerInbox;
import com.footballmanagergamesimulator.model.TransferOffer;
import com.footballmanagergamesimulator.repository.ManagerInboxRepository;
import org.springframework.stereotype.Service;

/**
 * Produces original, event-driven football media stories for human managers.
 * The tone mirrors familiar sports-news patterns (pressure, momentum, tactical
 * talking points and supporter reaction) without quoting or impersonating a
 * real publication or pundit.
 */
@Service
public class MediaNarrativeService {

    private final ManagerInboxRepository inboxRepository;

    public MediaNarrativeService(ManagerInboxRepository inboxRepository) {
        this.inboxRepository = inboxRepository;
    }

    public void publishPostMatchReaction(long teamId, String teamName,
                                         long opponentTeamId, String opponentName,
                                         int teamScore, int opponentScore,
                                         String competitionName, int season, int round) {
        String deduplicationKey = "MEDIA_MATCH:" + season + ":" + round + ":" + teamId + ":"
                + opponentTeamId + ":" + teamScore + ":" + opponentScore;
        if (inboxRepository.existsByTeamIdAndDeduplicationKey(teamId, deduplicationKey)) return;

        int margin = teamScore - opponentScore;
        int totalGoals = teamScore + opponentScore;
        String title;
        String verdict;
        String pressure;

        if (margin >= 3) {
            title = teamName + " run riot as " + opponentName + " are swept aside";
            verdict = "The studio panel praised the attacking intent and called the performance a statement to the rest of the competition.";
            pressure = "The next challenge is to turn a spectacular result into sustained form.";
        } else if (margin > 0) {
            title = teamName + " hold their nerve in victory over " + opponentName;
            verdict = "Post-match analysis focused on the decisive moments and the team's ability to protect a narrow advantage.";
            pressure = "Supporters will now expect the same concentration in the next fixture.";
        } else if (margin == 0 && totalGoals >= 4) {
            title = "No winner after a thriller between " + teamName + " and " + opponentName;
            verdict = "The match was described as excellent entertainment, although both defensive units came under scrutiny.";
            pressure = "The manager may be asked whether greater control should have replaced the end-to-end approach.";
        } else if (margin == 0) {
            title = "Questions remain after " + teamName + " are held by " + opponentName;
            verdict = "The main talking point was whether the side created enough to turn possession into three points.";
            pressure = "Attention now shifts to selection and attacking sharpness before the next match.";
        } else if (margin <= -3) {
            title = teamName + " under scrutiny after heavy defeat";
            verdict = "The panel questioned the team's reaction after falling behind and highlighted visible gaps between the units.";
            pressure = "Supporters are looking for accountability and an immediate response from the dressing room.";
        } else {
            title = "Pressure builds after " + teamName + " fall to " + opponentName;
            verdict = "Analysis centred on missed moments, game management and whether the tactical plan changed quickly enough.";
            pressure = "The manager's next team selection is likely to attract close attention.";
        }

        String content = "MEDIA WATCH\n\n"
                + competitionName + ": " + teamName + " " + teamScore + "-" + opponentScore + " " + opponentName + ".\n\n"
                + "Studio verdict: " + verdict + "\n\n"
                + "The talking point: " + pressure;

        ManagerInbox message = new ManagerInbox();
        message.setTeamId(teamId);
        message.setSeasonNumber(season);
        message.setRoundNumber(round);
        message.setTitle(title);
        message.setContent(content);
        message.setCategory("MEDIA_REACTION");
        message.setRead(false);
        message.setCreatedAt(System.currentTimeMillis());
        message.setAudience(InboxAudience.MANAGER);
        message.setDeduplicationKey(deduplicationKey);
        inboxRepository.save(message);
    }

    /**
     * Publishes speculation rooted in a real offer while explicitly preserving
     * uncertainty. The offer remains the authoritative transfer state; this is
     * only the media layer and can therefore be wrong, stale or superseded.
     */
    public void publishTransferRumour(TransferOffer offer, int round) {
        if (offer == null || offer.getId() <= 0) return;
        int credibility = rumourCredibility(offer);
        String source = rumourSource(offer);
        String title = credibility >= 75
                ? offer.getFromTeamName() + " step up pursuit of " + offer.getPlayerName()
                : offer.getPlayerName() + " linked with " + offer.getFromTeamName();
        String amount = String.format("€%,d", Math.max(0L, offer.getOfferAmount()));
        String statusLine = credibility >= 75
                ? "Sources believe contact between the clubs has moved beyond initial interest."
                : "The link is at an early stage and no agreement is believed to be close.";
        String content = "TRANSFER RUMOUR\n\n"
                + "Source: " + source + "\n"
                + "Credibility: " + credibility + "% · " + credibilityLabel(credibility) + "\n\n"
                + offer.getFromTeamName() + " are being linked with a move for " + offer.getPlayerName()
                + " of " + offer.getToTeamName() + ", with figures around " + amount + " being discussed.\n\n"
                + statusLine + " Neither club has publicly confirmed the story.";
        publishRumourForTeam(offer, offer.getFromTeamId(), round, title, content);
        if (offer.getToTeamId() != offer.getFromTeamId()) {
            publishRumourForTeam(offer, offer.getToTeamId(), round, title, content);
        }
    }

    private void publishRumourForTeam(TransferOffer offer, long teamId, int round, String title, String content) {
        String key = "TRANSFER_RUMOUR:" + offer.getId() + ":" + teamId;
        if (inboxRepository.existsByTeamIdAndDeduplicationKey(teamId, key)) return;
        ManagerInbox message = new ManagerInbox();
        message.setTeamId(teamId);
        message.setSeasonNumber(offer.getSeasonNumber());
        message.setRoundNumber(round);
        message.setTitle(title);
        message.setContent(content);
        message.setCategory("TRANSFER_RUMOUR");
        message.setRead(false);
        message.setCreatedAt(System.currentTimeMillis());
        message.setAudience(InboxAudience.MANAGER);
        message.setDeduplicationKey(key);
        inboxRepository.save(message);
    }

    private int rumourCredibility(TransferOffer offer) {
        int base = "counter".equalsIgnoreCase(offer.getStatus()) || "negotiating".equalsIgnoreCase(offer.getStatus()) ? 80 : 55;
        return Math.min(92, base + (int) Math.floorMod(offer.getPlayerId() * 13L + offer.getId() * 7L, 13L));
    }

    private String rumourSource(TransferOffer offer) {
        String[] sources = {"agent circles", "club-connected reporters", "regional football desk", "dressing-room contacts"};
        return sources[(int) Math.floorMod(offer.getPlayerId() + offer.getFromTeamId(), sources.length)];
    }

    private String credibilityLabel(int credibility) {
        if (credibility >= 85) return "strong information";
        if (credibility >= 70) return "developing story";
        return "early speculation";
    }
}
