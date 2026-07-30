package com.footballmanagergamesimulator.transfermarket;

/** One player offered on the AI transfer market. */
public class PlayerTransferView {

  private long playerId;
  /** Selling club, or 0 for a free agent (nobody to pay, nobody to notify). */
  private long teamId;
  private double rating;
  /** Base position — the market's discrete index key, permissively collapsed. */
  private String position;
  /** Natural position as recorded on the player, kept for familiarity lookups. */
  private String naturalPosition;

  private long age;
  private boolean willNeverLeave;
  private boolean starter;

  public PlayerTransferView(long playerId, long teamId, double rating, String position,
                            String naturalPosition, long age, boolean willNeverLeave,
                            boolean starter) {
    this.playerId = playerId;
    this.teamId = teamId;
    this.rating = rating;
    this.position = position;
    this.naturalPosition = naturalPosition;
    this.age = age;
    this.willNeverLeave = willNeverLeave;
    this.starter = starter;
  }

  public boolean isFreeAgent() {
    return teamId == 0L;
  }

  public long getPlayerId() {
    return playerId;
  }

  public void setPlayerId(long playerId) {
    this.playerId = playerId;
  }

  public long getTeamId() {
    return teamId;
  }

  public void setTeamId(long teamId) {
    this.teamId = teamId;
  }

  public double getRating() {
    return rating;
  }

  public void setRating(double rating) {
    this.rating = rating;
  }

  public String getPosition() {
    return position;
  }

  public void setPosition(String position) {
    this.position = position;
  }

  public String getNaturalPosition() {
    return naturalPosition;
  }

  public void setNaturalPosition(String naturalPosition) {
    this.naturalPosition = naturalPosition;
  }

  public long getAge() {
    return age;
  }

  public void setAge(long age) {
    this.age = age;
  }

  public boolean isWillNeverLeave() {
    return willNeverLeave;
  }

  public void setWillNeverLeave(boolean willNeverLeave) {
    this.willNeverLeave = willNeverLeave;
  }

  /** True when the selling club fielded him in its best XI. */
  public boolean isStarter() {
    return starter;
  }

  public void setStarter(boolean starter) {
    this.starter = starter;
  }
}
