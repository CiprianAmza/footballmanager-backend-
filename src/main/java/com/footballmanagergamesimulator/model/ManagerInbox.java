package com.footballmanagergamesimulator.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
@Table(name="manager_inbox")
public class ManagerInbox {

    @Id
    @GeneratedValue(strategy= GenerationType.IDENTITY)
    private long id;

    private long teamId;
    private int seasonNumber;
    private int roundNumber;

    private String title;

    @Column(columnDefinition = "TEXT")
    private String content;

    private String category;

    @JsonProperty("isRead")
    private boolean isRead;

    private long createdAt;

    private Long recipientProfileId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private InboxAudience audience = InboxAudience.MANAGER;

    private String deduplicationKey;

}
