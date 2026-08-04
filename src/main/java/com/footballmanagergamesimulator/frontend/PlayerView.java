package com.footballmanagergamesimulator.frontend;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.sql.Date;
import java.util.List;

@Data
public class PlayerView {

  private long id;
  private String name;
  private long teamId;
  private String teamName;
  private String position;
  private double rating;
  private int age;

  private double bestEverRating;
  private int seasonOfBestEverRating;

  private long salary;
  private String agreedPlayingTime;
  private Date contractEndDate;
  private Date contractStartDate;

  private int contractEndSeason;
  private long wage;
  private long releaseClause;
  private long transferValue;
  private boolean willNeverLeave;
  private boolean stayForward;

  /**
   * Whether the player has ended his career.
   *
   * <p>Sent explicitly rather than inferred from a missing club. Retirement used to
   * be the only way to end up without one, so {@code teamId == null} was a safe
   * stand-in; now that contracts expire into free agency it is not, and a free agent
   * would read as retired.
   *
   * <p>The JSON name is pinned: Jackson derives {@code retired} from the getter
   * {@code isRetired()} on its own, which is not the name the client reads.
   */
  @JsonProperty("isRetired")
  private boolean retired;

  /** True when he has no club but is still playing — available on a free transfer. */
  @JsonProperty("isFreeAgent")
  private boolean freeAgent;

  private double fitness;
  private double morale;
  private String currentStatus;

  private long seasonCreated;
  private long wealth;

  List<String> skillNames;
  List<Long> skillValues;
  private int seasonAppearances;
  private int seasonGoals;
  private int seasonAssists;
  private List<PlayerAttributeView> importantAttributes;

  // Recruitment status for current-season filters and loan highlighting.
  private String marketStatus;
  private boolean transferredThisSeason;
  private boolean loanedThisSeason;
  private boolean loaned;
  private long parentTeamId;
  private String parentTeamName;
  private long loanTeamId;
  private String loanTeamName;

  // Physical profile
  private String preferredFoot;
  private int heightCm;
  private int weightKg;

  // Nation (derived: team -> competition -> nationId)
  private long nationId;
  private String nationName;
  private String nationFlagCode;

  // Face descriptor (FE renders layered pieces from these indices)
  private int baseFaceId;
  private int skinTone;
  private int hairStyle;
  private int hairColor;
  private int eyeColor;
  // Shape indices (independent of colour) — picked randomly per player on the backend.
  private int faceShape;
  private int noseShape;
  private int eyeShape;
  private int mouthShape;
  private int browShape;
  // Exotic species (whole-nation mapping); "human" = default earthly face.
  private String species;
}
