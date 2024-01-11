package com.footballmanagergamesimulator.transfermarket;

public class PlayerTransferView {

  private long playerId;
  private long teamId;
  private long desiredReputation;
  private double rating;
  private String position;

  private long age;

  public PlayerTransferView(long playerId, long teamId, long desiredReputation, double rating, String position, long age) {
    this.playerId = playerId;
    this.teamId = teamId;
    this.desiredReputation = desiredReputation;
    this.rating = rating;
    this.position = position;
    this.age = age;
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

  public long getDesiredReputation() {
    return desiredReputation;
  }

  public void setDesiredReputation(long desiredReputation) {
    this.desiredReputation = desiredReputation;
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

  public long getAge() {
    return age;
  }

  public void setAge(long age) {
    this.age = age;
  }
}
