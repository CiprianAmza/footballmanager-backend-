package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
@Table(name = "match_event",
        uniqueConstraints = @UniqueConstraint(columnNames = {"fixture_key", "slot_index", "event_type"}))
public class MatchEvent {

    public static final int ORDER_GOAL = 0;
    public static final int ORDER_ASSIST = 1;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    // Canonical MatchPlan provenance. Null/-1 for legacy synthetic events (flag
    // off); the unique (fixture_key, slot_index, event_type) then holds one goal
    // and one assist row per slot, making persistence idempotent on retry/refresh.
    // NULL fixture_key rows are distinct under the constraint, so the legacy path
    // is unaffected.
    @Column(name = "fixture_key")
    private String fixtureKey;

    // Boxed so rows imported from saves that predate match plans (NULL here) still
    // load; the int accessors below expose them as the legacy -1.
    @Column(name = "slot_index")
    private Integer slotIndex = -1;

    /** Stable ordering inside one goal slot (goal before its optional assist). */
    @Column(name = "event_order")
    private Integer eventOrder = -1;

    private long competitionId;
    private int seasonNumber;
    private int roundNumber;
    private long teamId1; // home team
    private long teamId2; // away team
    @Column(name = "event_minute")
    private int minute; // 1 to 90
    @Column(name = "event_type")
    private String eventType; // "goal", "assist", "yellow_card", "red_card", "substitution"
    private long playerId;
    private String playerName;
    private long teamId; // which team the event belongs to
    private String details; // extra info like "Header from corner", "Long range shot"

    public int getSlotIndex() {
        return slotIndex == null ? -1 : slotIndex;
    }

    public void setSlotIndex(int slotIndex) {
        this.slotIndex = slotIndex;
    }

    public int getEventOrder() {
        return eventOrder == null ? -1 : eventOrder;
    }

    public void setEventOrder(int eventOrder) {
        this.eventOrder = eventOrder;
    }
}
