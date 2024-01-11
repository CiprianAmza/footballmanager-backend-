package com.footballmanagergamesimulator.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(value = NON_NULL)
public class Player {

    private String name;
    private String age;
    private String team;


    public String getName() {
        return name;
    }

    public String getAge() {
        return age;
    }

    public String getTeam() {
        return team;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setAge(String age) {
        this.age = age;
    }

    public void setTeam(String team) {
        this.team = team;
    }
}
