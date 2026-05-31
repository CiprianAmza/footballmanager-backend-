package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

/**
 * A personal asset owned by a {@link Human} (manager) as part of their
 * extra-sporting "Boardroom" life. Houses and cars are pure wealth stores;
 * SHARES assets represent a stake in a club and carry a {@link #clubTeamId}.
 *
 * <p>Note: club shareholdings are tracked authoritatively in
 * {@link ClubShareholding}. A SHARES Asset row is a convenience mirror so the
 * unified asset list can show shares alongside houses/cars; the % stake lives
 * on the shareholding entity.
 */
@Entity
@Data
@Table(name = "asset")
public class Asset {

    public enum AssetType { HOUSE, CAR, SHARES }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    /** Owning human (manager) id. */
    private long ownerHumanId;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private AssetType type;

    private String name;

    /** Current market value of the asset, in the same currency units as wealth.
     *  Column renamed off the reserved word VALUE (H2 SQL keyword). */
    @Column(name = "asset_value")
    private long value;

    /** For SHARES assets: the club the shares belong to. 0 / null otherwise. */
    @Column(columnDefinition = "bigint default 0")
    private Long clubTeamId;
}
