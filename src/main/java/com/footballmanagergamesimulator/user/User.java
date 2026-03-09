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

    private Long teamId;       // the team this user manages (null = not yet selected)
    private Long lastTeamId;   // the last team managed (preserved when fired, for inbox access)
    private Long managerId;    // the Human entity ID for this user's manager
    private boolean fired;     // true if this user's manager was sacked (per-user, not global)
}
