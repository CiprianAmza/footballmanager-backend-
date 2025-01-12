package com.footballmanagergamesimulator.extra;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.persistence.*;
import lombok.*;

import java.util.Date;

@Entity
@Data
@Table(name = "flight")
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class Flight {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    @JsonFormat(pattern = "departure")
    private String departure;
    @JsonFormat(pattern = "departureAbbreviation")
    private String departureAbbreviation;
    @JsonFormat(pattern = "departureCity")
    private String departureCity;
    @JsonFormat(pattern = "arrival")
    private String arrival;
    @JsonFormat(pattern = "arrivalAbbreviation")
    private String arrivalAbbreviation;
    @JsonFormat(pattern = "arrivalCity")
    private String arrivalCity;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private Date departureDate;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private Date arrivalDate;

    @JsonFormat(pattern = "price")
    private double price;
    @JsonFormat(pattern = "flightDuration")
    private Integer flightDuration;
    @JsonFormat(pattern = "planeCode")
    private String planeCode;
    @JsonFormat(pattern = "url")
    private String url;
    @JsonFormat(pattern = "yearValue")
    private String yearValue;
    @JsonFormat(pattern = "monthValue")
    private String monthValue;
    @JsonFormat(pattern = "dayValue")
    private String dayValue;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getDeparture() {
        return departure;
    }

    public void setDeparture(String departure) {
        this.departure = departure;
    }

    public String getDepartureAbbreviation() {
        return departureAbbreviation;
    }

    public void setDepartureAbbreviation(String departureAbbreviation) {
        this.departureAbbreviation = departureAbbreviation;
    }

    public String getDepartureCity() {
        return departureCity;
    }

    public void setDepartureCity(String departureCity) {
        this.departureCity = departureCity;
    }

    public String getArrival() {
        return arrival;
    }

    public void setArrival(String arrival) {
        this.arrival = arrival;
    }

    public String getArrivalAbbreviation() {
        return arrivalAbbreviation;
    }

    public void setArrivalAbbreviation(String arrivalAbbreviation) {
        this.arrivalAbbreviation = arrivalAbbreviation;
    }

    public String getArrivalCity() {
        return arrivalCity;
    }

    public void setArrivalCity(String arrivalCity) {
        this.arrivalCity = arrivalCity;
    }

    public Date getDepartureDate() {
        return departureDate;
    }

    public void setDepartureDate(Date departureDate) {
        this.departureDate = departureDate;
    }

    public Date getArrivalDate() {
        return arrivalDate;
    }

    public void setArrivalDate(Date arrivalDate) {
        this.arrivalDate = arrivalDate;
    }

    public double getPrice() {
        return price;
    }

    public void setPrice(double price) {
        this.price = price;
    }

    public Integer getFlightDuration() {
        return flightDuration;
    }

    public void setFlightDuration(Integer flightDuration) {
        this.flightDuration = flightDuration;
    }

    public String getPlaneCode() {
        return planeCode;
    }

    public void setPlaneCode(String planeCode) {
        this.planeCode = planeCode;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getYearValue() {
        return yearValue;
    }

    public void setYearValue(String yearValue) {
        this.yearValue = yearValue;
    }

    public String getMonthValue() {
        return monthValue;
    }

    public void setMonthValue(String monthValue) {
        this.monthValue = monthValue;
    }

    public String getDayValue() {
        return dayValue;
    }

    public void setDayValue(String dayValue) {
        this.dayValue = dayValue;
    }
}
