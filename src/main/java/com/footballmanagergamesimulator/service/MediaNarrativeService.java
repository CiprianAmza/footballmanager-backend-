package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.InboxAudience;
import com.footballmanagergamesimulator.model.ManagerInbox;
import com.footballmanagergamesimulator.model.TransferOffer;
import com.footballmanagergamesimulator.repository.ManagerInboxRepository;
import com.footballmanagergamesimulator.service.press.MatchReactionArticle;
import com.footballmanagergamesimulator.service.press.PressDeskService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Produces original, event-driven football media stories for human managers.
 * The tone mirrors familiar sports-news patterns (pressure, momentum, tactical
 * talking points and supporter reaction) without quoting or impersonating a
 * real publication or pundit.
 *
 * <p>Each story is written and signed by one of the league's reporters, in his own voice
 * ({@link PressDeskService}, design DESIGN_START_CARIERA §6.1.1). The plain texts below are what is published when
 * the press desk has nothing to offer (no reporter, no fragment).
 */
@Service
public class MediaNarrativeService {

    private final ManagerInboxRepository inboxRepository;

    @Autowired(required = false)
    private PressDeskService pressDesk;

    public MediaNarrativeService(ManagerInboxRepository inboxRepository) {
        this.inboxRepository = inboxRepository;
    }

    private record Story(String title, String content) {
    }

    /** The reaction to a match whose saved numbers are not known to the caller. */
    public void publishPostMatchReaction(long teamId, String teamName,
                                         long opponentTeamId, String opponentName,
                                         int teamScore, int opponentScore,
                                         String competitionName, int season, int round) {
        publishPostMatchReaction(new MatchReactionArticle.Facts(teamId, teamName, opponentTeamId, opponentName,
                teamScore, opponentScore, 0, competitionName, season, round, 0, 0));
    }

    public void publishPostMatchReaction(MatchReactionArticle.Facts facts) {
        long teamId = facts.teamId();
        String deduplicationKey = "MEDIA_MATCH:" + facts.season() + ":" + facts.round() + ":" + teamId + ":"
                + facts.opponentTeamId() + ":" + facts.teamScore() + ":" + facts.opponentScore();
        if (inboxRepository.existsByTeamIdAndDeduplicationKey(teamId, deduplicationKey)) return;

        Story story = signed(pressDesk == null ? Optional.empty() : pressDesk.matchReaction(facts))
                .orElseGet(() -> plainReaction(facts));

        ManagerInbox message = new ManagerInbox();
        message.setTeamId(teamId);
        message.setSeasonNumber(facts.season());
        message.setRoundNumber(facts.round());
        message.setTitle(story.title());
        message.setContent(story.content());
        message.setCategory("MEDIA_REACTION");
        message.setRead(false);
        message.setCreatedAt(System.currentTimeMillis());
        message.setAudience(InboxAudience.MANAGER);
        message.setDeduplicationKey(deduplicationKey);
        inboxRepository.save(message);
    }

    private static Optional<Story> signed(Optional<PressDeskService.Piece> piece) {
        return piece.map(written -> new Story(written.headline(), written.body()));
    }

    private static Story plainReaction(MatchReactionArticle.Facts facts) {
        String teamName = facts.teamName();
        String opponentName = facts.opponentName();
        int margin = facts.teamScore() - facts.opponentScore();
        int totalGoals = facts.teamScore() + facts.opponentScore();
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
                + facts.competitionName() + ": " + teamName + " " + facts.teamScore() + "-" + facts.opponentScore()
                + " " + opponentName + ".\n\n"
                + "Studio verdict: " + verdict + "\n\n"
                + "The talking point: " + pressure;
        return new Story(title, content);
    }

    /**
     * Publishes speculation rooted in a real offer while explicitly preserving
     * uncertainty. The offer remains the authoritative transfer state; this is
     * only the media layer and can therefore be wrong, stale or superseded.
     */
    public void publishTransferRumour(TransferOffer offer, int round) {
        if (offer == null || offer.getId() <= 0) return;
        List<Long> readers = new ArrayList<>();
        readers.add(offer.getFromTeamId());
        if (offer.getToTeamId() != offer.getFromTeamId()) readers.add(offer.getToTeamId());
        readers.removeIf(teamId -> inboxRepository.existsByTeamIdAndDeduplicationKey(teamId, rumourKey(offer, teamId)));
        if (readers.isEmpty()) return;

        Story story = signed(pressDesk == null ? Optional.empty() : pressDesk.transferRumour(offer, round))
                .orElseGet(() -> plainRumour(offer));
        for (long teamId : readers) {
            publishRumourForTeam(offer, teamId, round, story.title(), story.content());
        }
    }

    private Story plainRumour(TransferOffer offer) {
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
        return new Story(title, content);
    }

    private static String rumourKey(TransferOffer offer, long teamId) {
        return "TRANSFER_RUMOUR:" + offer.getId() + ":" + teamId;
    }

    private void publishRumourForTeam(TransferOffer offer, long teamId, int round, String title, String content) {
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
        message.setDeduplicationKey(rumourKey(offer, teamId));
        inboxRepository.save(message);
    }

    /** Talks between the clubs (or a fee already agreed) make a stronger story than a first bid. */
    static int rumourCredibility(TransferOffer offer) {
        String status = offer.getStatus();
        boolean advanced = TransferOffer.STATUS_TALKS.equalsIgnoreCase(status)
                || TransferOffer.STATUS_FINAL_OFFER.equalsIgnoreCase(status)
                || TransferOffer.STATUS_FEE_AGREED.equalsIgnoreCase(status);
        int base = advanced ? 80 : 55;
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
