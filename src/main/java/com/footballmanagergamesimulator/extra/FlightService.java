package com.footballmanagergamesimulator.extra;

import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
public class FlightService {

    /**
     * Sets the flight duration of a given Flight object based on the difference
     * between its departureDate and arrivalDate in minutes.
     *
     * @param flight the Flight object whose flightDuration needs to be set.
     */
    public void setFlightDuration(Flight flight) {

        if (flight.getDepartureDate() != null && flight.getArrivalDate() != null) {
            long diffInMillis = flight.getArrivalDate().getTime() - flight.getDepartureDate().getTime();
            int durationInMinutes = (int) TimeUnit.MILLISECONDS.toMinutes(diffInMillis);
            flight.setFlightDuration(durationInMinutes);
        } else {
            throw new IllegalArgumentException("Departure date or arrival date is null");
        }
    }

    /**
     * Validates the fields of the given Flight object.
     *
     * @param flight the Flight object to validate.
     * @throws IllegalArgumentException if any validation rule is violated.
     */
    public void validateFlight(Flight flight) {

        if (flight.getDeparture() == null || flight.getDeparture().isEmpty()) {
            throw new IllegalArgumentException("Departure cannot be null or empty");
        }
        if (flight.getArrival() == null || flight.getArrival().isEmpty()) {
            throw new IllegalArgumentException("Arrival cannot be null or empty");
        }
        if (flight.getDepartureDate() == null) {
            throw new IllegalArgumentException("Departure date cannot be null");
        }
        if (flight.getArrivalDate() == null) {
            throw new IllegalArgumentException("Arrival date cannot be null");
        }
        if (flight.getDepartureDate().after(flight.getArrivalDate())) {
            throw new IllegalArgumentException("Departure date must be before arrival date");
        }
        if (flight.getPrice() <= 0) {
            throw new IllegalArgumentException("Price must be greater than 0");
        }
    }
}