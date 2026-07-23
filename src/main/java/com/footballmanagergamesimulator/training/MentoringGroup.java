package com.footballmanagergamesimulator.training;

import jakarta.persistence.*;
import lombok.Data;

/**
 * A mentoring group: a small set of players in which experienced mentors
 * influence the mental development of younger mentees. Members are stored in
 * {@link MentoringGroupMember}.
 */
@Entity
@Data
@Table(name = "mentoringGroup")
public class MentoringGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    private long teamId;
    private String name;

    @Version
    private long version;
}
