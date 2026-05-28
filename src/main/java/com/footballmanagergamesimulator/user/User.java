package com.footballmanagergamesimulator.user;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;


@Entity
@Getter
@Setter
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;
    private String username;
    private String password;
    private String email;
    private String firstName;
    private String lastName;
    private String toBeUpgraded = "";
    private boolean active;
    private String roles = "USER";

    private Long teamId;       // the team this user manages (null = not yet selected OR free agent)
    private Long lastTeamId;   // the last team managed (preserved when fired, for inbox access)
    private Long managerId;    // the Human entity ID for this user's manager
    private boolean fired;     // true if user has no team (sacked OR started as free agent — drives FE job-search UI)
    private boolean everManaged; // true once the user has accepted any team. Distinguishes "fired veteran" from "fresh free agent".
    private boolean initialOffersGenerated; // true once we've spawned the welcome batch of offers for a free agent (prevents re-spam).
}
