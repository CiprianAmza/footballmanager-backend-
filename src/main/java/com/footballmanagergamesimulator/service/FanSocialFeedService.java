package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.InboxAudience;
import com.footballmanagergamesimulator.model.ManagerInbox;
import com.footballmanagergamesimulator.repository.ManagerInboxRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/** Creates deterministic, event-driven supporter posts for the club social feed. */
@Service
public class FanSocialFeedService {

    public static final String CATEGORY = "SOCIAL_FAN_POST";

    private static final String[] HANDLES = {
            "@TerraceVoice", "@AwayDayFaithful", "@ClubTillIDie", "@TacticsAndTea",
            "@NorthStandView", "@TheFinalWhistle", "@OneClubHeart", "@MondayMatchTalk",
            "@OldSchoolUltra", "@YouthEnd", "@NoNonsenseFan", "@MatchdayPulse"
    };

    private final ManagerInboxRepository inbox;

    public FanSocialFeedService(ManagerInboxRepository inbox) {
        this.inbox = inbox;
    }

    public void publishPostMatchPosts(long teamId, String teamName, String opponentName,
                                      int teamScore, int opponentScore, String competitionName,
                                      int season, int day) {
        int margin = teamScore - opponentScore;
        List<PostDraft> posts;
        if (margin >= 3) {
            posts = List.of(
                    new PostDraft("ECSTATIC", "That was football with the volume turned all the way up. " + opponentName + " could not live with us."),
                    new PostDraft("POSITIVE", teamName + " played with courage, tempo and purpose. More of that every week, please."),
                    new PostDraft("HUMOR", "Can we play them again tomorrow? Asking for the entire stand."),
                    new PostDraft("DEMANDING", "Brilliant result, but the real test is backing it up. One great afternoon cannot be the ceiling."));
        } else if (margin > 0) {
            posts = List.of(
                    new PostDraft("POSITIVE", "Three points and a proper shift. Not perfect, but everyone fought for the badge."),
                    new PostDraft("ANALYTICAL", "The game management after taking the lead was much better today. That mattered."),
                    new PostDraft("DEMANDING", "Happy with the win, still worried by how easily " + opponentName + " reached our box."));
        } else if (margin == 0) {
            String harsh = teamScore == 0
                    ? "That was painfully flat. All that possession and barely a moment that made the crowd believe."
                    : "We cannot keep giving away control and calling a draw a good day. The standards have to be higher.";
            posts = List.of(
                    new PostDraft("FRUSTRATED", harsh),
                    new PostDraft("ANALYTICAL", "A point, but the final pass kept letting us down. The structure was there; the conviction was not."),
                    new PostDraft("LOYAL", "Frustrating result, but I will be there next match. Sort the details and go again."));
        } else if (margin <= -3) {
            posts = List.of(
                    new PostDraft("HARSH", "That was embarrassing. No intensity, no control and no excuses after conceding " + opponentScore + "."),
                    new PostDraft("ANGRY", "Too many players hid when the match turned against us. The manager got the response completely wrong."),
                    new PostDraft("CRITICAL", "Supporters pay, travel and sing. They deserve more than watching the same mistakes every week."),
                    new PostDraft("LOYAL", "A horrible day, but the club is bigger than one result. Now show us a real reaction."));
        } else {
            posts = List.of(
                    new PostDraft("HARSH", "No more soft excuses. We lost the decisive moments and the manager has to own that."),
                    new PostDraft("CRITICAL", "The plan looked far too passive once " + opponentName + " scored. Why did we wait so long to change it?"),
                    new PostDraft("LOYAL", "I am angry tonight, but still behind the team. Effort and a response are non-negotiable next time."));
        }

        String context = competitionName + ": " + teamName + " " + teamScore + "-" + opponentScore + " " + opponentName;
        savePosts(teamId, season, day, "MATCH:" + opponentName + ":" + teamScore + ":" + opponentScore,
                context, posts);
    }

    public void publishFinancialPosts(long teamId, String teamName, int season, int day, String severity) {
        boolean critical = "CRITICAL".equals(severity);
        List<PostDraft> posts = critical ? List.of(
                new PostDraft("ANGRY", "How did the board let " + teamName + " reach this point? Supporters deserve a full explanation, not another vague statement."),
                new PostDraft("HARSH", "If emergency player sales are the plan, the people who created this mess should be held accountable first."),
                new PostDraft("WORRIED", "Forget glamorous signings. Protect the club, pay the bills and give us a sustainable plan.")) : List.of(
                new PostDraft("WORRIED", "The numbers around " + teamName + " are uncomfortable. The board needs to explain the plan before rumours take over."),
                new PostDraft("CRITICAL", "Do not make supporters pay for poor budgeting with higher prices and a weaker squad."),
                new PostDraft("LOYAL", "We will stand by the club, but loyalty cannot mean silence. Transparency matters."));
        savePosts(teamId, season, day, "FINANCE:" + severity, "Club finances · " + severity, posts);
    }

    private void savePosts(long teamId, int season, int day, String eventKey,
                           String context, List<PostDraft> posts) {
        for (int index = 0; index < posts.size(); index++) {
            String key = "SOCIAL_FEED:" + season + ":" + day + ":" + teamId + ":" + eventKey + ":" + index;
            if (inbox.existsByTeamIdAndDeduplicationKey(teamId, key)) continue;
            PostDraft post = posts.get(index);
            ManagerInbox message = new ManagerInbox();
            message.setTeamId(teamId);
            message.setSeasonNumber(season);
            message.setRoundNumber(day);
            message.setTitle(handle(teamId, season, day, index));
            message.setContent(post.tone() + "\n" + context + "\n" + post.body());
            message.setCategory(CATEGORY);
            message.setRead(true);
            message.setCreatedAt(System.currentTimeMillis() + index);
            message.setAudience(InboxAudience.MANAGER);
            message.setDeduplicationKey(key);
            inbox.save(message);
        }
    }

    private String handle(long teamId, int season, int day, int index) {
        long seed = teamId * 31L + season * 17L + day * 7L + index * 5L;
        return HANDLES[(int) Math.floorMod(seed, HANDLES.length)];
    }

    private record PostDraft(String tone, String body) {}
}
