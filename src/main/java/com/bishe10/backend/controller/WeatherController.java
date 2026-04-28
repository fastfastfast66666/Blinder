package com.bishe10.backend.controller;

import com.bishe10.backend.service.LocationResolver;
import com.bishe10.backend.service.WeatherService;
import com.bishe10.backend.support.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class WeatherController {

    private final WeatherService weatherService;
    private final LocationResolver locationResolver;

    public WeatherController(WeatherService weatherService, LocationResolver locationResolver) {
        this.weatherService = weatherService;
        this.locationResolver = locationResolver;
    }

    @GetMapping("/api/weather/local")
    public Map<String, Object> localWeather(
            @RequestParam(required = false) String city,
            @RequestParam(required = false) Double lat,
            @RequestParam(required = false) Double lng
    ) {
        LocationResolver.ResolvedLocation resolvedLocation = locationResolver.resolve(lat, lng, city);
        return ApiResponse.ok(weatherService.buildLocalWeather(lat, lng, resolvedLocation.city()));
    }
}
