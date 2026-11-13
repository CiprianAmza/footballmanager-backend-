package com.footballmanagergamesimulator.service.press;

import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.repository.*;
import com.footballmanagergamesimulator.service.TrainingDelegationService;
import com.footballmanagergamesimulator.transfermarket.TransferStrategyUtil;
import com.footballmanagergamesimulator.util.TypeNames;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * The assistant's briefing on arrival (design DESIGN_START_CARIERA §6.3, decided 2026-11-12): transfers in
 * progress, contracts running out, unhappy players, planned training camps, who runs training, and the club's
 * philosophy (for now its transfer strategy, only presented). The assistant is the game's voice, not a person.
 */
@Service
public class ArrivalBriefingService {

    static final String CATEGORY = "assistant";
    static final double UNHAPPY_MORALE = 60;
    static final List<String> LIVE_OUTGOING = List.of(TransferOffer.STATUS_TALKS, TransferOffer.STATUS_FINAL_OFFER,
            TransferOffer.STATUS_FEE_AGREED, "pending");

    /** The club's transfer strategy, told as a philosophy. */
    static final Map<Long, String> PHILOSOPHY = Map.of(
            TransferStrategyUtil.TRANSFER_STRATEGY_ACADEMY,
            "We grow our own. The academy is the heart of the club and the first team should be built from it.",
            TransferStrategyUtil.TRANSFER_STRATEGY_BUY_YOUNG_SELL_HIGH,
            "We buy young talent, develop it and sell at the peak. Player trading keeps the club alive.",
            TransferStrategyUtil.TRANSFER_STRATEGY_BUY_FREE_SELL_HIGH,
            "We look for players nobody else wants to pay for, and we sell when the price is right.",
            TransferStrategyUtil.TRANSFER_STRATEGY_BUY_MID_SELL_MID,
            "Balance above all: no risky spending, no fire sales, a steady squad.",
            TransferStrategyUtil.TRANSFER_STRATEGY_BUY_TOP_SELL_WORST,
            "We bring in stars and move on those who are not good enough.",
            TransferStrategyUtil.TRANSFER_STRATEGY_BUY_TOP_SELL_TOP,
            "We are a big club: we buy and sell at the very top of the market.");

    private final HumanRepository humanRepository;
    private final TransferOfferRepository offerRepository;
    private final FriendlyEventRepository friendlyEventRepository;
    private final TeamRepository teamRepository;
    private final GameCalendarRepository calendarRepository;
    private final ManagerInboxRepository inboxRepository;
    private final TrainingDelegationService trainingDelegationService;

    public ArrivalBriefingService(HumanRepository humanRepository, TransferOfferRepository offerRepository,
                                  FriendlyEventRepository friendlyEventRepository, TeamRepository teamRepository,
                                  GameCalendarRepository calendarRepository, ManagerInboxRepository inboxRepository,
                                  TrainingDelegationService trainingDelegationService) {
        this.humanRepository = humanRepository;
        this.offerRepository = offerRepository;
        this.friendlyEventRepository = friendlyEventRepository;
        this.teamRepository = teamRepository;
        this.calendarRepository = calendarRepository;
        this.inboxRepository = inboxRepository;
        this.trainingDelegationService = trainingDelegationService;
    }

    /** Writes the briefing for the new manager of {@code clubId} into the club's inbox. */
    @Transactional
    public Optional<String> publish(Human manager, long clubId) {
        Team club = teamRepository.findById(clubId).orElse(null);
        if (club == null) return Optional.empty();
        GameCalendar calendar = calendarRepository.findTopByOrderBySeasonDesc().orElse(null);
        int season = calendar == null ? 1 : calendar.getSeason();
        int day = calendar == null ? 1 : calendar.getCurrentDay();
        String body = write(manager, club, season, day);
        ManagerInbox inbox = new ManagerInbox();
        inbox.setTeamId(clubId);
        inbox.setSeasonNumber(season);
        inbox.setRoundNumber(day);
        inbox.setTitle("Assistant: your first briefing at " + club.getName());
        inbox.setContent(body);
        inbox.setCategory(CATEGORY);
        inbox.setRead(false);
        inbox.setCreatedAt(System.currentTimeMillis());
        inboxRepository.save(inbox);
        return Optional.of(body);
    }

    String write(Human manager, Team club, int season, int day) {
        List<Human> squad = humanRepository.findAllByTeamIdAndTypeId(club.getId(), TypeNames.PLAYER_TYPE).stream()
                .filter(p -> !p.isRetired()).toList();
        List<String> sections = new ArrayList<>();
        sections.add("Welcome, boss. Here is where things stand at " + club.getName() + ".");
        sections.add(section("Transfers in progress", transfers(club.getId())));
        sections.add(section("Contracts running out", expiring(squad, season)));
        sections.add(section("Unhappy players", unhappy(squad)));
        sections.add(section("Training camps", camps(club.getId(), season, day)));
        sections.add(section("How we train", training(club.getId())));
        String philosophy = club.getStrategy() == null ? null : PHILOSOPHY.get(club.getStrategy());
        sections.add("CLUB PHILOSOPHY\n" + (philosophy != null ? philosophy
                : "The board has never written one down. That gives you room to shape it."));
        return String.join("\n\n", sections);
    }

    private static String section(String title, List<String> lines) {
        String text = lines.isEmpty() ? "Nothing to report." : String.join("\n", lines);
        return title.toUpperCase(Locale.ROOT) + "\n" + text;
    }

    List<String> transfers(long clubId) {
        List<String> lines = new ArrayList<>();
        for (String status : LIVE_OUTGOING) {
            for (TransferOffer offer : offerRepository.findAllByFromTeamIdAndStatus(clubId, status)) {
                lines.add("- We are after " + offer.getPlayerName() + " (" + offer.getToTeamName() + "): " + describe(status) + ".");
            }
        }
        for (TransferOffer offer : offerRepository.findAllByToTeamIdAndStatus(clubId, "pending")) {
            lines.add(String.format(Locale.ROOT, "- %s want %s: an offer of %,d is waiting for an answer.",
                    offer.getFromTeamName(), offer.getPlayerName(), offer.getOfferAmount()));
        }
        return lines;
    }

    private static String describe(String status) {
        return switch (status) {
            case TransferOffer.STATUS_TALKS -> "talks with the club are open";
            case TransferOffer.STATUS_FINAL_OFFER -> "the club has made a final offer";
            case TransferOffer.STATUS_FEE_AGREED -> "the fee is agreed, personal terms are pending";
            default -> "an offer is on the table";
        };
    }

    /** Contracts ending this season or the next, soonest first. */
    List<String> expiring(List<Human> squad, int season) {
        return squad.stream()
                .filter(p -> p.getContractEndSeason() <= season + 1)
                .sorted(Comparator.comparingInt(Human::getContractEndSeason).thenComparing(Human::getName))
                .map(p -> "- " + p.getName() + " (" + p.getPosition() + ", " + p.getAge() + "): contract ends "
                        + (p.getContractEndSeason() <= season ? "this season" : "next season") + ".")
                .toList();
    }

    /** Players asking to leave or low on morale. */
    List<String> unhappy(List<Human> squad) {
        return squad.stream()
                .filter(p -> p.isWantsTransfer() || p.getMorale() < UNHAPPY_MORALE)
                .sorted(Comparator.comparingDouble(Human::getMorale))
                .map(p -> "- " + p.getName() + " (" + p.getPosition() + "): "
                        + (p.isWantsTransfer() ? "wants to leave" : "unhappy")
                        + (p.getConsecutiveBenched() >= 3 ? ", left out of the last " + p.getConsecutiveBenched() + " matches" : "")
                        + ".")
                .toList();
    }

    /** Camps the club organises from today on. */
    List<String> camps(long clubId, int season, int day) {
        return friendlyEventRepository.findAllByOrganizerTeamIdAndSeasonOrderByStartDayAsc(clubId, season).stream()
                .filter(e -> "TRAINING_CAMP".equals(e.getEventType()) && e.getEndDay() >= day)
                .map(e -> "- " + e.getName() + (e.getLocationName() == null ? "" : " in " + e.getLocationName())
                        + ", days " + e.getStartDay() + "-" + e.getEndDay()
                        + (e.getFocus() == null ? "" : ", focus: " + e.getFocus().toLowerCase(Locale.ROOT)) + ".")
                .toList();
    }

    /** Who runs each kind of training, and how good he is at it. */
    List<String> training(long clubId) {
        List<String> lines = new ArrayList<>();
        for (TrainingDelegationService.TypeOverview type : trainingDelegationService.overview(clubId)) {
            String coach = type.effectiveStaffId() == null ? "nobody"
                    : humanRepository.findById(type.effectiveStaffId()).map(Human::getName).orElse("nobody");
            lines.add(String.format(Locale.ROOT, "- %s: %s (%.0f/20)%s", label(type.type().name()), coach,
                    type.effectiveRating(), type.effectiveStaffId() == null ? "" : type.automatic() ? ", chosen by the staff" : ""));
        }
        return lines;
    }

    private static String label(String enumName) {
        String lower = enumName.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}
