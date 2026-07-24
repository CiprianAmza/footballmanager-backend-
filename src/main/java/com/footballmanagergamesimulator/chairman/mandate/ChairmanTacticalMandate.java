package com.footballmanagergamesimulator.chairman.mandate;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Entity
@Table(name = "chairman_tactical_mandate", uniqueConstraints = @UniqueConstraint(
        name = "uk_chairman_tactical_mandate_team", columnNames = "team_id"))
@Getter
@Setter
@NoArgsConstructor
public class ChairmanTacticalMandate {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    @Column(name = "team_id", nullable = false, unique = true)
    private long teamId;

    private String requiredFormation;
    private long updatedByProfileId;
    private int updatedSeason;
    private int updatedGameDay;

    @Version
    private long version;

    @OneToMany(mappedBy = "mandate", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<MandateSlot> slots = new ArrayList<>();

    public List<MandateSlot> sortedSlots() {
        return slots.stream().sorted(Comparator.comparingInt(MandateSlot::getPositionIndex)
                .thenComparingLong(MandateSlot::getRequiredPlayerId)).toList();
    }

    /** Public/entity view is deterministic even if persistence returns another order. */
    public List<MandateSlot> getSlots() {
        return sortedSlots();
    }

    public void replaceSlots(List<MandateSlot> replacement) {
        slots.clear();
        replacement.forEach(slot -> { slot.setMandate(this); slots.add(slot); });
    }
}
