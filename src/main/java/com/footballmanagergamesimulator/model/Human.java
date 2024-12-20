package com.footballmanagergamesimulator.model;

import jakarta.persistence.*;
import lombok.Data;

import java.sql.Date;

@Entity
@Data
@Table(name="human")
public class Human {

    @Id
    @GeneratedValue(strategy=GenerationType.IDENTITY)
    private long id;

    /**
     *  Relation ids
     */
    private long teamId;
    private long agentId;
    private long skillsId;
    private long typeId;

    /**
     * General information
     */
    private int age;
    private int shirtNumber;
    private long salary;
    private long wealth;
    private String name;
    private String position;
    private String agreedPlayingTime;
    private Date contractEndDate;
    private Date contractStartDate;

    /**
     * Stats information
     */
    private int currentAbility;
    private int potentialAbility;
    private long transferValue;
    private double rating;
    private double fitness;
    private String morale;
    private String currentStatus;
    private long seasonCreated;

    /**
     * Manager information
     */
    private String tacticStyle;

}
