package com.footballmanagergamesimulator.extra;

import com.footballmanagergamesimulator.extra.Flight;
import com.footballmanagergamesimulator.extra.FlightRepository;
import com.footballmanagergamesimulator.extra.FlightService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/flight")
@CrossOrigin(origins = "*")
public class FlightController {


    @Autowired
    FlightRepository flightRepository;
    @Autowired
    FlightService flightService;

    @GetMapping("/allFlights")
    public List<Flight> getAllFlights() {

        return flightRepository.findAll().stream().limit(100).collect(Collectors.toList());
    }

    @GetMapping("/allFlightsTo/{city}")
    public List<Flight> getAllFlightsToCity(@PathVariable(name = "city") String city) {

        List<Flight> allFlightsToCity = flightRepository
                .findAll()
                .stream()
                .filter(flight -> flight.getArrivalCity().equals(city))
                .limit(200)
                .collect(Collectors.toList());

        return allFlightsToCity;
    }

    @PostMapping("/addFlight")
    public ResponseEntity<Flight> addFlight(@RequestBody Flight flight) {

        flightService.setFlightDuration(flight);
        Flight savedFlight = flightRepository.save(flight);

        return ResponseEntity.ok(savedFlight);
    }


}
