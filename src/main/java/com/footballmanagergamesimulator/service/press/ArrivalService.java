package com.footballmanagergamesimulator.service.press;

import com.footballmanagergamesimulator.model.Human;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Everything that happens when a human manager takes a club (design DESIGN_START_CARIERA §6): the press article
 * on the appointment, then the assistant's briefing. Later steps (meetings, press conference) join here.
 */
@Service
public class ArrivalService {

    private final AppointmentArticleService article;
    private final ArrivalBriefingService briefing;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.footballmanagergamesimulator.service.board.BoardTrustService boardTrust;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.footballmanagergamesimulator.service.board.StaffLoyaltyService staffLoyalty;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.footballmanagergamesimulator.service.board.FrontCoachService frontCoach;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.footballmanagergamesimulator.service.board.BoardMeetingService boardMeeting;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private FirstDayService firstDay;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.footballmanagergamesimulator.repository.GameCalendarRepository calendars;

    public ArrivalService(AppointmentArticleService article, ArrivalBriefingService briefing) {
        this.article = article;
        this.briefing = briefing;
    }

    @Transactional
    public void welcome(Human manager, long clubId) {
        if (boardTrust != null) boardTrust.managerAppointed(manager.getId(), clubId);
        if (staffLoyalty != null) staffLoyalty.managerAppointed(clubId);
        if (frontCoach != null) frontCoach.managerAppointed(clubId);
        if (boardMeeting != null) boardMeeting.managerAppointed(manager.getId(), clubId);
        article.publish(manager, clubId);
        briefing.publish(manager, clubId);
        if (firstDay != null) {
            int season = calendars == null ? 1 : calendars.findTopByOrderBySeasonDesc()
                    .map(com.footballmanagergamesimulator.model.GameCalendar::getSeason).orElse(1);
            firstDay.start(manager, clubId, season);
        }
    }
}
