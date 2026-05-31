package com.footballmanagergamesimulator.frontend;

import lombok.Data;

@Data
public class PlayerCardView {

    private long playerId;
    private String name;
    private String position;
    private int overall;
    private int pac;
    private int sho;
    private int pas;
    private int dri;
    private int def;
    private int phy;
    private int age;
    private Long nationId;
    private Object faceDescriptor;
}
