package com.footballmanagergamesimulator.multiplayer;

import com.footballmanagergamesimulator.model.GameCalendar;

public record RoomDate(int season, int day) implements Comparable<RoomDate> {
    public static RoomDate of(GameCalendar calendar) {
        return new RoomDate(calendar.getSeason(), calendar.getCurrentDay());
    }

    @Override
    public int compareTo(RoomDate other) {
        int seasonComparison = Integer.compare(season, other.season);
        return seasonComparison != 0 ? seasonComparison : Integer.compare(day, other.day);
    }

    public boolean isAfter(RoomDate other) { return compareTo(other) > 0; }
    public boolean equalsDate(RoomDate other) { return season == other.season && day == other.day; }
}
