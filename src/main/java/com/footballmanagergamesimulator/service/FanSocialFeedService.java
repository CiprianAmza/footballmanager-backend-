package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.InboxAudience;
import com.footballmanagergamesimulator.model.ManagerInbox;
import com.footballmanagergamesimulator.repository.ManagerInboxRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Creates deterministic, event-driven supporter posts for the club social feed. */
@Service
public class FanSocialFeedService {

    public static final String CATEGORY = "SOCIAL_FAN_POST";

    private static final String[] HANDLES = {
            "@TerraceVoice", "@AwayDayFaithful", "@ClubTillIDie", "@TacticsAndTea",
            "@NorthStandView", "@TheFinalWhistle", "@OneClubHeart", "@MondayMatchTalk",
            "@OldSchoolUltra", "@YouthEnd", "@NoNonsenseFan", "@MatchdayPulse",
            "@HalfTimeNotebook", "@TheBackRow", "@SundaySupporter", "@LastTrainHome"
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
                    new PostDraft("DEMANDING", "Brilliant result, but the real test is backing it up. One great afternoon cannot be the ceiling."),
                    new PostDraft("ANALYTICAL", "Scoring " + teamScore + " against " + opponentName + " is a statement. The next challenge is repeating it."));
        } else if (margin > 0) {
            posts = List.of(
                    new PostDraft("POSITIVE", "A win and a proper shift. Not perfect, but everyone fought for the badge."),
                    new PostDraft("ANALYTICAL", "That was a narrow win over " + opponentName + ". The margin left little room for error."),
                    new PostDraft("DEMANDING", "Happy with the win, but a narrow lead leaves no room to switch off."),
                    new PostDraft("HUMOR", "My nerves would appreciate a wider winning margin next time."),
                    new PostDraft("LOYAL", "Close or comfortable, I will take a win over " + opponentName + " every time."));
        } else if (margin == 0) {
            String harsh = teamScore == 0
                    ? "That was painfully flat. No goals for either side and barely a moment that made the crowd believe."
                    : "We cannot keep giving away control and calling a draw a good day. The standards have to be higher.";
            posts = List.of(
                    new PostDraft("FRUSTRATED", harsh),
                    new PostDraft("ANALYTICAL", "A point against " + opponentName + ", but no winner. The decisive moment never came."),
                    new PostDraft("LOYAL", "Frustrating result, but I will be there next match. Sort the details and go again."),
                    new PostDraft("CRITICAL", "A point is not a substitute for a convincing performance. Show us more next time."),
                    new PostDraft("HUMOR", "That settled absolutely nothing. At least the argument can continue all week."));
        } else if (margin <= -3) {
            posts = List.of(
                    new PostDraft("HARSH", "That was embarrassing. No intensity, no control and no excuses after conceding " + opponentScore + "."),
                    new PostDraft("ANGRY", "Too many players hid when the match turned against us. The manager got the response completely wrong."),
                    new PostDraft("CRITICAL", "Supporters pay, travel and sing. They deserve more than watching the same mistakes every week."),
                    new PostDraft("LOYAL", "A horrible day, but the club is bigger than one result. Now show us a real reaction."),
                    new PostDraft("ANALYTICAL", "A defeat by " + Math.abs(margin) + " goals is too big to call bad luck. Start with the basics."));
        } else {
            posts = List.of(
                    new PostDraft("HARSH", "No more soft excuses. We lost the decisive moments and the manager has to own that."),
                    new PostDraft("CRITICAL", "The plan did not deliver against " + opponentName + ". The manager needs a better answer next time."),
                    new PostDraft("LOYAL", "I am angry tonight, but still behind the team. Effort and a response are non-negotiable next time."),
                    new PostDraft("FRUSTRATED", "That result will sting all week. I expected more against " + opponentName + "."),
                    new PostDraft("ANALYTICAL", "The margin was small, but it decided the match. We need to do better in those moments."));
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
                new PostDraft("WORRIED", "Forget glamorous signings. Protect the club, pay the bills and give us a sustainable plan."),
                new PostDraft("LOYAL", "I will stand by " + teamName + ", but loyalty does not mean silence about the finances.")) : List.of(
                new PostDraft("WORRIED", "The numbers around " + teamName + " are uncomfortable. The board needs to explain the plan before rumours take over."),
                new PostDraft("CRITICAL", "Do not make supporters pay for poor budgeting with higher prices and a weaker squad."),
                new PostDraft("LOYAL", "We will stand by the club, but loyalty cannot mean silence. Transparency matters."),
                new PostDraft("ANALYTICAL", "A sustainable wage bill matters as much as the next transfer fee. Show us the long-term plan."));
        savePosts(teamId, season, day, "FINANCE:" + severity, "Club finances · " + severity, posts);
    }

    /** Recompose previously saved feed copy without changing its match context or identity. */
    public int refreshExistingPosts(long teamId) {
        int updated = 0;
        for (ManagerInbox message : inbox.findAllByTeamIdAndCategoryOrderByIdDesc(teamId, CATEGORY)) {
            if (message.getContent() == null || message.getDeduplicationKey() == null) continue;
            String[] parts = message.getContent().split("\\n", 3);
            if (parts.length != 3) continue;
            boolean financial = message.getDeduplicationKey().contains(":FINANCE:");
            String[] alternatives = financial ? financeAlternatives(parts[0]) : matchAlternatives(parts[0]);
            String body = compose(new PostDraft(parts[0], alternatives[1]),
                    message.getDeduplicationKey(), financial, parts[1]);
            String content = parts[0] + "\n" + parts[1] + "\n" + body;
            if (content.equals(message.getContent())) continue;
            message.setContent(content);
            inbox.save(message);
            updated++;
        }
        return updated;
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
            message.setContent(post.tone() + "\n" + context + "\n" +
                    compose(post, key, eventKey.startsWith("FINANCE:"), context));
            message.setCategory(CATEGORY);
            message.setRead(true);
            message.setCreatedAt(System.currentTimeMillis() + index);
            message.setAudience(InboxAudience.MANAGER);
            message.setDeduplicationKey(key);
            inbox.save(message);
        }
    }

    private String compose(PostDraft post, String key, boolean financial, String context) {
        String[] openings = financial ? financeOpenings(post.tone()) : matchOpenings(post.tone());
        String[] middles = financial ? financeAlternatives(post.tone()) : matchAlternatives(post.tone());
        String[] endings = financial
                ? new String[]{"The board needs to be clear with supporters.", "Show us a credible plan.", "The club's future matters more than a press release."}
                : matchEndings(post.tone());
        String middle = choose(new String[]{post.body(), middles[0], middles[1]}, key, 1);
        String detail = financial ? "" : matchDetail(context, key);
        return choose(openings, key, 0) + " " + middle + " " + detail + choose(endings, key, 2);
    }

    private String matchDetail(String context, String key) {
        Matcher score = Pattern.compile("(\\d+)-(\\d+) (.+)$").matcher(context);
        if (!score.find()) return "";
        int own = Integer.parseInt(score.group(1));
        int opponent = Integer.parseInt(score.group(2));
        String name = score.group(3);
        String result = own > opponent ? "win over" : own < opponent ? "loss to" : "draw with";
        String[] details = {
                "That " + own + "-" + opponent + " " + result + " " + name + " will stay in the conversation. ",
                "The " + own + "-" + opponent + " against " + name + " tells its own story. ",
                "Everyone will have a view on the " + own + "-" + opponent + " against " + name + ". "
        };
        return choose(details, key, 3);
    }

    private String choose(String[] options, String key, int part) {
        long seed = ((long) key.hashCode() << 32) ^ key.length()
                ^ (0x9E3779B97F4A7C15L * (part + 1));
        seed ^= seed >>> 33;
        seed *= 0xff51afd7ed558ccdL;
        seed ^= seed >>> 33;
        seed *= 0xc4ceb9fe1a85ec53L;
        seed ^= seed >>> 33;
        return options[(int) Math.floorMod(seed, options.length)];
    }

    private String[] matchOpenings(String tone) {
        return switch (tone) {
            case "ECSTATIC" -> new String[]{"What a day for this club.", "I could watch that again tonight.", "That result will live long in the memory."};
            case "POSITIVE" -> new String[]{"Credit where it is due.", "The players delivered today.", "That feels good."};
            case "HUMOR" -> new String[]{"The group chat is going to be busy.", "My nerves would like a word.", "I have several completely measured opinions about that."};
            case "ANALYTICAL" -> new String[]{"The scoreline tells a story.", "There is a lesson in that result.", "A few details decided the day."};
            case "LOYAL" -> new String[]{"Still here, still backing the badge.", "Being a supporter means showing up again.", "The club is bigger than one result."};
            case "DEMANDING" -> new String[]{"Good result, but keep the standards high.", "Enjoy it, then reset.", "That cannot be the ceiling."};
            case "HARSH", "ANGRY" -> new String[]{"That was unacceptable.", "No way to dress that up.", "Supporters deserve better."};
            default -> new String[]{"There are questions after that one.", "I left the ground wanting more.", "The result deserves an honest look."};
        };
    }

    private String[] matchAlternatives(String tone) {
        return switch (tone) {
            case "ECSTATIC", "POSITIVE" -> new String[]{"A win like that lifts the whole stand.", "The result gives everyone something to celebrate."};
            case "HUMOR" -> new String[]{"I need to read the post-match reactions twice.", "Everyone in the stand will have a different take on this."};
            case "ANALYTICAL" -> new String[]{"One result does not define the season, but it changes the mood.", "The manager will have plenty to review before the next match."};
            case "LOYAL" -> new String[]{"Loyalty does not stop us asking for more.", "I will still be there at the next whistle."};
            case "DEMANDING" -> new String[]{"The challenge is producing that level consistently.", "The next opponent will be a fresh test."};
            case "HARSH", "ANGRY" -> new String[]{"The scoreline is too bad to call it a minor setback.", "The manager and players both need to take responsibility."};
            default -> new String[]{"The scoreboard gave us less than we wanted.", "The team needs a better answer next time."};
        };
    }

    private String[] matchEndings(String tone) {
        return switch (tone) {
            case "ECSTATIC", "POSITIVE", "HUMOR" -> new String[]{"More of that, please.", "Enjoy this one.", "See you at the next match."};
            case "HARSH", "ANGRY", "CRITICAL" -> new String[]{"No excuses this time.", "Show us what changes.", "A response is overdue."};
            case "LOYAL" -> new String[]{"We go again.", "I will be there.", "Earn the crowd's trust."};
            default -> new String[]{"The work continues.", "The next match matters.", "Keep the standards high."};
        };
    }

    private String[] financeOpenings(String tone) {
        return switch (tone) {
            case "ANGRY", "HARSH" -> new String[]{"The board owes supporters answers.", "This is not a problem the fans created.", "No more spin about the accounts."};
            case "WORRIED" -> new String[]{"These figures are uncomfortable.", "I am worried about the club's future.", "Forget the next flashy signing for a moment."};
            default -> new String[]{"We need a serious conversation about the finances.", "Backing the club and asking questions go together.", "The warning deserves a clear response."};
        };
    }

    private String[] financeAlternatives(String tone) {
        return switch (tone) {
            case "ANGRY", "HARSH" -> new String[]{"Poor decisions must have consequences for the people who made them.", "Supporters should not carry the whole cost of bad planning."};
            case "WORRIED" -> new String[]{"Protecting the club matters more than short-term headlines.", "A sustainable plan matters more than promises."};
            default -> new String[]{"A wage bill needs to make sense over a full season.", "Transparency is the first step towards rebuilding trust."};
        };
    }

    private String handle(long teamId, int season, int day, int index) {
        long seed = teamId * 31L + season * 17L + day * 7L + index * 5L;
        return HANDLES[(int) Math.floorMod(seed, HANDLES.length)];
    }

    private record PostDraft(String tone, String body) {}
}
