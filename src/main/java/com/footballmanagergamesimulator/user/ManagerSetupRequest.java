package com.footballmanagergamesimulator.user;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * The new manager (design DESIGN_START_CARIERA §2.2). Everything after {@code freeAgent} is optional: left out,
 * the manager has no playing career and no licence.
 */
public record ManagerSetupRequest(
        @NotBlank @Size(max = 100) String managerName,
        @Min(18) @Max(90) int managerAge,
        Long teamId,
        boolean freeAgent,
        @Min(1) @Max(31) Integer birthDay,
        @Min(1) @Max(12) Integer birthMonth,
        Long birthNationId,
        Long favoriteTeamId,
        @Size(max = 20) String preferredTactic,
        PlayingCareer playingCareer,
        CoachingLicence coachingLicence,
        @Valid @Size(max = 30) List<PlayingSeason> playingSeasons,
        @Valid Face face
) {
    public ManagerSetupRequest(String managerName, int managerAge, Long teamId, boolean freeAgent) {
        this(managerName, managerAge, teamId, freeAgent, null, null, null, null, null, null, null, null, null);
    }

    /**
     * The face built in the creation editor: the same indices players' faces use (see FaceGenerator). The species
     * is not chosen: it follows the nation of birth.
     */
    public record Face(@Min(0) @Max(9) int baseFaceId,
                       @Min(0) @Max(5) int skinTone,
                       @Min(0) @Max(19) int hairStyle,
                       @Min(0) @Max(5) int hairColor,
                       @Min(0) @Max(3) int eyeColor,
                       @Min(0) @Max(17) int faceShape,
                       @Min(0) @Max(9) int noseShape,
                       @Min(0) @Max(9) int eyeShape,
                       @Min(0) @Max(4) int mouthShape,
                       @Min(0) @Max(8) int browShape) {
    }

    /** One season as a footballer: the club, goals and assists. */
    public record PlayingSeason(@NotBlank @Size(max = 60) String teamName,
                                @Min(0) @Max(200) int goals,
                                @Min(0) @Max(200) int assists) {
    }
}
