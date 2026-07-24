package com.footballmanagergamesimulator.chairman.mandate;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "chairman_tactical_mandate_slot", uniqueConstraints = {
        @UniqueConstraint(name = "uk_mandate_slot_position", columnNames = {"mandate_id", "position_index"}),
        @UniqueConstraint(name = "uk_mandate_slot_player", columnNames = {"mandate_id", "required_player_id"})
})
@Getter
@Setter
@NoArgsConstructor
public class MandateSlot {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mandate_id", nullable = false)
    private ChairmanTacticalMandate mandate;

    @Column(name = "position_index", nullable = false)
    private int positionIndex;

    @Column(name = "required_player_id", nullable = false)
    private long requiredPlayerId;

    public MandateSlot(int positionIndex, long requiredPlayerId) {
        this.positionIndex = positionIndex;
        this.requiredPlayerId = requiredPlayerId;
    }
}
