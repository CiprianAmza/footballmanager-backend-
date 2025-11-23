package com.footballmanagergamesimulator.model;


import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
@Table(name="personalizedTactic")
public class PersonalizedTactic {

    @Id
    @GeneratedValue(strategy= GenerationType.IDENTITY)
    private long id;

    private long teamId;
    @Column(length = 10000)
    private String first11;
    private String tactic;

    private String mentality;
    private String timeWasting;
    private String inPossession;
    private String passingType;
    private String tempo;
}
