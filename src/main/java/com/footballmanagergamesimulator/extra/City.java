package com.footballmanagergamesimulator.extra;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
@Table(name="city")
public class City {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    @JsonFormat(pattern = "name")
    private String name;
    @JsonFormat(pattern = "airportCode")
    private String airportCode;
}