package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
@Table(name = "stadium")
public class Stadium {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    private long teamId;
    private String stadiumName;

    // Capacity
    private int capacity = 30000;
    private int expansionLevel = 0; // 0-10, each level adds capacity

    // Revenue-boosting facilities (levels 0-10)
    private int vipBoxesLevel = 0;
    private int cateringLevel = 0;
    private int fanShopLevel = 0;
    private int fastFoodLevel = 0;

    // Infrastructure
    private int headquartersLevel = 1;
    private int trainingPitchLevel = 1;
    private int parkingLevel = 0;

    /**
     * Calculate total effective capacity including expansions.
     * Each expansion level adds 5000 seats.
     */
    public int getEffectiveCapacity() {
        return capacity + (expansionLevel * 5000);
    }

    /**
     * Calculate match day revenue multiplier from stadium facilities.
     * Base = 1.0, each facility level adds a bonus.
     */
    public double getRevenueMultiplier() {
        double multiplier = 1.0;
        multiplier += vipBoxesLevel * 0.08;   // VIP: +8% per level (max +80%)
        multiplier += cateringLevel * 0.04;    // Catering: +4% per level (max +40%)
        multiplier += fanShopLevel * 0.03;     // Fan shop: +3% per level (max +30%)
        multiplier += fastFoodLevel * 0.03;    // Fast food: +3% per level (max +30%)
        multiplier += parkingLevel * 0.02;     // Parking: +2% per level (max +20%)
        return multiplier;
    }
}
