package com.expensetracker.households.api;

import com.expensetracker.households.application.HouseholdQueryService;
import com.expensetracker.households.application.HouseholdService;
import com.expensetracker.households.application.HouseholdView;
import com.expensetracker.identity.application.Actor;
import com.expensetracker.identity.security.SessionPrincipal;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/households")
public class HouseholdController {
    private final HouseholdService households;
    private final HouseholdQueryService queries;

    public HouseholdController(HouseholdService households, HouseholdQueryService queries) {
        this.households = households;
        this.queries = queries;
    }

    @GetMapping("/")
    public List<HouseholdView> list(@AuthenticationPrincipal SessionPrincipal principal) {
        return queries.listFor(new Actor(principal.userId()));
    }

    @PostMapping("/")
    public ResponseEntity<HouseholdView> create(@AuthenticationPrincipal SessionPrincipal principal,
            @Valid @RequestBody CreateHouseholdRequest request) {
        HouseholdView created = households.create(new Actor(principal.userId()), request.name());
        return ResponseEntity.created(URI.create("/api/v1/households/" + created.id() + "/")).body(created);
    }

    @GetMapping("/{householdId}/")
    public HouseholdView get(@AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable UUID householdId) {
        return queries.get(new Actor(principal.userId()), householdId);
    }
}
