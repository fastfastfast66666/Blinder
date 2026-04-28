package com.bishe10.backend.service;

import com.bishe10.backend.config.Bishe10Properties;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class LocationResolverTests {

    private final LocationResolver resolver = new LocationResolver(new Bishe10Properties(), new ObjectMapper());

    @Test
    void farAwayCoordinatesFallBackToDefaultCity() {
        LocationResolver.ResolvedLocation location = resolver.resolve(38.8833, -77.0, null);

        assertThat(location.city()).isEqualTo("上海");
        assertThat(location.source()).isEqualTo("default");
        assertThat(location.permission()).isEqualTo("unknown");
    }

    @Test
    void nearbyCoordinatesStillUseApproximateCity() {
        LocationResolver.ResolvedLocation location = resolver.resolve(30.5728, 104.0668, null);

        assertThat(location.city()).isEqualTo("成都");
        assertThat(location.source()).isEqualTo("gps");
        assertThat(location.permission()).isEqualTo("granted");
    }
}
