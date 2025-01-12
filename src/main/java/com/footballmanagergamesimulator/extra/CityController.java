package com.footballmanagergamesimulator.extra;


import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/cities")
@CrossOrigin(origins = "*")
public class CityController {

    @Autowired
    CityRepository cityRepository;

    @GetMapping("/allCities")
    public List<City> findAllCities() {

        return cityRepository.findAll();
    }

    @PostMapping("/addCity")
    public ResponseEntity<City> addCity(@RequestBody City city) {

        City savedCity = cityRepository.save(city);
        return ResponseEntity.ok(savedCity);
    }
}
