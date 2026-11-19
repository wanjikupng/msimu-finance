package com.tracom.lipamdogo.controller;

import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Deployment health probe.
 *
 * <p>Public, unauthenticated and intentionally free of application data. It
 * answers the question a platform health check actually cares about: is the
 * process serving requests, can it reach its database, and has startup finished
 * (including the demo portfolio seed)? A platform only routes traffic once this
 * returns 200, so assessors never land on a half-seeded application.</p>
 */
@RestController
@RequestMapping("/api/v1")
public class HealthController {

    private final JdbcTemplate jdbcTemplate;
    private final ApplicationAvailability availability;

    public HealthController(JdbcTemplate jdbcTemplate, ApplicationAvailability availability) {
        this.jdbcTemplate = jdbcTemplate;
        this.availability = availability;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        boolean databaseUp;
        try {
            Integer probe = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            databaseUp = probe != null && probe == 1;
        } catch (RuntimeException ex) {
            databaseUp = false;
        }

        ReadinessState readiness = availability.getReadinessState();
        boolean acceptingTraffic = readiness == ReadinessState.ACCEPTING_TRAFFIC;
        boolean healthy = databaseUp && acceptingTraffic;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", !databaseUp ? "DOWN" : acceptingTraffic ? "UP" : "STARTING");
        body.put("application", "lipa-mdogo-mdogo");
        body.put("database", databaseUp ? "UP" : "DOWN");
        body.put("readiness", readiness.name());
        body.put("timestamp", Instant.now().toString());

        return healthy
                ? ResponseEntity.ok(body)
                : ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }
}
