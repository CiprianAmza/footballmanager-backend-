package com.footballmanagergamesimulator.person;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
@Table(name = "person_profile", uniqueConstraints = {
        @UniqueConstraint(name = "uk_person_profile_user", columnNames = "user_id"),
        @UniqueConstraint(name = "uk_person_profile_human", columnNames = "human_id")
})
public class PersonProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    @Column(name = "user_id", unique = true)
    private Integer userId;

    @Column(name = "human_id", unique = true)
    private Long humanId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CareerType careerType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ControlType controlType;

    @Column(nullable = false)
    private String displayName;

    @Column(nullable = false)
    private int createdSeason;

    @Column(nullable = false)
    private int createdDay;

    @Column(nullable = false)
    private boolean active = true;

    @Column(nullable = false)
    private boolean retired;
}
