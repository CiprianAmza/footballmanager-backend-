package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
@Table(name="transfer_offer")
public class TransferOffer {

    /** The selling club accepted my fee; the transfer waits for personal terms with the player's agent. */
    public static final String STATUS_FEE_AGREED = "fee_agreed";
    /** Personal terms failed after the fee was agreed. */
    public static final String STATUS_TERMS_FAILED = "terms_failed";
    /** Round-based fee talks with the selling club are going on (they do not block the day). */
    public static final String STATUS_TALKS = "talks";
    /** The selling club put its current ask as a take-it-or-leave-it offer. */
    public static final String STATUS_FINAL_OFFER = "final_offer";
    /** Talks with the selling club ended without a deal. */
    public static final String STATUS_REJECTED = "rejected";
    public static final java.util.List<String> OPEN_TALKS = java.util.List.of(STATUS_TALKS, STATUS_FINAL_OFFER);

    /** Fee talks: the round to play next and how many the club allows (0 = old one-shot offer). */
    @Column(columnDefinition = "integer default 0 not null")
    private int round;
    @Column(columnDefinition = "integer default 0 not null")
    private int rounds;
    /** Fee talks: the least the selling club takes (hidden from the buyer). */
    @Column(columnDefinition = "bigint default 0 not null")
    private long minimumPrice;
    /** Fee talks that failed: no new talks for this player with this club before this game day (season×365+day). */
    @Column(columnDefinition = "integer default 0 not null")
    private int retryAfterDay;

    // Extras of my offer (design §3.5). offerAmount is the cash paid now.
    @Column(columnDefinition = "integer default 0 not null")
    private int installmentCount;
    @Column(columnDefinition = "bigint default 0 not null")
    private long installmentAmount;
    @Column(columnDefinition = "integer default 0 not null")
    private int sellOnPercent;
    /** 0 = no buy-back clause. */
    @Column(columnDefinition = "bigint default 0 not null")
    private long buyBackPrice;
    /** Performance bonuses: a sum when the player scores this many goals in a season for me; a sum for my league title. */
    @Column(columnDefinition = "integer default 0 not null")
    private int goalsBonusTarget;
    @Column(columnDefinition = "bigint default 0 not null")
    private long goalsBonusAmount;
    @Column(columnDefinition = "bigint default 0 not null")
    private long titleBonusAmount;
    /** Fee talks: the player's real price and the selling club's situation, fixed when the talks open. */
    @Column(columnDefinition = "bigint default 0 not null")
    private long realPrice;
    private String sellerSituation;

    @Id
    @GeneratedValue(strategy= GenerationType.IDENTITY)
    private long id;

    private long playerId;
    private String playerName;
    private long fromTeamId;
    private String fromTeamName;
    private long toTeamId;
    private String toTeamName;
    private long offerAmount;
    private long askingPrice;
    private String status;
    private int seasonNumber;
    private String direction;
    private long createdAt;

}
