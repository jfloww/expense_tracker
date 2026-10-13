package com.expensetracker.shared.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
    record HealthResponse(String status, String service) {}

    @GetMapping("/api/v1/health/")
    public HealthResponse health() {
        return new HealthResponse("ok", "expense-tracker");
    }
}
