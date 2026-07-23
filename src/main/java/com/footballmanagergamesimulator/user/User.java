package com.footballmanagergamesimulator.user;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;


@Entity
@Getter
@Setter
@Table(name = "users", uniqueConstraints = {
        @UniqueConstraint(name = "uk_users_username", columnNames = "username"),
        @UniqueConstraint(name = "uk_users_email", columnNames = "email")
})
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;
    @Column(nullable = false, length = 64)
    private String username;
    @JsonIgnore
    @Column(nullable = false, length = 100)
    private String password;
    @Column(nullable = false, length = 254)
    private String email;
    private String firstName;
    private String lastName;
    private String toBeUpgraded = "";
    private boolean active;
    @Column(nullable = false)
    private String roles = "USER";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CareerRole careerRole = CareerRole.MANAGER;

    private Long teamId;       // the team this user manages (null = not yet selected OR free agent)
    private Long lastTeamId;   // the last team managed (preserved when fired, for inbox access)
    private Long managerId;    // the Human entity ID for this user's manager
    private boolean fired;     // true if user has no team (sacked OR started as free agent — drives FE job-search UI)
    private boolean everManaged; // true once the user has accepted any team. Distinguishes "fired veteran" from "fresh free agent".
    private boolean initialOffersGenerated; // true once we've spawned the welcome batch of offers for a free agent (prevents re-spam).
}
