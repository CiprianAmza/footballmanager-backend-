package com.footballmanagergamesimulator.service.press;

import com.footballmanagergamesimulator.model.Analyst;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AppointmentArticleTest {

    @Test
    @DisplayName("""
            GIVEN a club of reputation 8,000
            WHEN managers of reputation 8,000, 6,000 and 5,000 are appointed
            THEN they are above, near (70% or more) and below the club; the decision reads natural, mixed, surprise
             and the headline positive, mixed, negative
            """)
    void reputationGapSetsTheTone() {
        assertEquals(AppointmentArticle.Gap.ABOVE, AppointmentArticle.gap(8_000, 8_000));
        assertEquals(AppointmentArticle.Gap.NEAR, AppointmentArticle.gap(6_000, 8_000));
        assertEquals(AppointmentArticle.Gap.BELOW, AppointmentArticle.gap(5_000, 8_000));
        assertEquals("SURPRISE", AppointmentArticle.decisionBucket(AppointmentArticle.Gap.BELOW));
        assertEquals("MIXED", AppointmentArticle.headlineBucket(AppointmentArticle.Gap.NEAR));
    }

    @Test
    @DisplayName("""
            GIVEN a 12-club league
            WHEN the club finished 1st, 3rd, 6th and 11th, and its managers lasted 1.2, 3 seasons or there is one
            THEN its context is champion, rising, declining, crisis; tenure short, long, no history
            """)
    void clubContextAndTenure() {
        assertEquals("CHAMPION", AppointmentArticle.contextBucket(1, 12));
        assertEquals("RISING", AppointmentArticle.contextBucket(3, 12));
        assertEquals("DECLINING", AppointmentArticle.contextBucket(6, 12));
        assertEquals("CRISIS", AppointmentArticle.contextBucket(11, 12));
        assertEquals("SHORT", AppointmentArticle.tenureBucket(1.2, 4));
        assertEquals("LONG", AppointmentArticle.tenureBucket(3.0, 3));
        assertEquals("NO_HISTORY", AppointmentArticle.tenureBucket(5.0, 1));
    }

    @Test
    @DisplayName("""
            GIVEN pundits supporting club 7, club 9 (a rival of 7) and no club
            WHEN each talks about club 7
            THEN they speak as a fan, as a rival, and neutrally; the bucket joins temperament, gap and sympathy
            """)
    void sympathyColoursThePundit() {
        Set<Long> rivalsOf7 = Set.of(9L, 4L);
        assertEquals(AppointmentArticle.Affinity.FAVORITE, AppointmentArticle.affinity(pundit(7), 7, rivalsOf7));
        assertEquals(AppointmentArticle.Affinity.RIVAL, AppointmentArticle.affinity(pundit(9), 7, rivalsOf7));
        assertEquals(AppointmentArticle.Affinity.NONE, AppointmentArticle.affinity(pundit(0), 7, rivalsOf7));
        assertEquals("CRITIC_BELOW_RIVAL", AppointmentArticle.analystBucket(
                Analyst.Temperament.CRITIC, AppointmentArticle.Gap.BELOW, AppointmentArticle.Affinity.RIVAL));
    }

    @Test
    @DisplayName("""
            GIVEN a fragment with {manager}, {club} and {lastPosition}
            WHEN it is filled
            THEN the values replace the placeholders; ordinals read 1st, 2nd, 3rd, 11th, 22nd
            """)
    void placeholdersAreFilled() {
        assertEquals("Kvekrpur takes Tik Tok, 3rd last season",
                AppointmentArticle.fill("{manager} takes {club}, {lastPosition} last season",
                        Map.of("manager", "Kvekrpur", "club", "Tik Tok", "lastPosition", AppointmentArticle.ordinal(3))));
        assertEquals("1st 2nd 11th 22nd", String.join(" ", AppointmentArticle.ordinal(1), AppointmentArticle.ordinal(2),
                AppointmentArticle.ordinal(11), AppointmentArticle.ordinal(22)));
    }

    private static Analyst pundit(long favorite) {
        Analyst analyst = new Analyst();
        analyst.setTemperament(Analyst.Temperament.BALANCED);
        analyst.setFavoriteTeamId(favorite);
        return analyst;
    }
}
