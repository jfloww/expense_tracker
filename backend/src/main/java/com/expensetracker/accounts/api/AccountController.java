package com.expensetracker.accounts.api;

import com.expensetracker.accounts.application.AccountQueryService;
import com.expensetracker.accounts.application.AccountService;
import com.expensetracker.accounts.application.AccountView;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/households/{householdId}/accounts")
public class AccountController {
    private final AccountService accounts;
    private final AccountQueryService queries;

    public AccountController(AccountService accounts, AccountQueryService queries) {
        this.accounts = accounts;
        this.queries = queries;
    }

    @GetMapping("/")
    public List<AccountView> list(@AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable UUID householdId) {
        return queries.list(new Actor(principal.userId()), householdId);
    }

    @PostMapping("/")
    public ResponseEntity<AccountView> create(@AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable UUID householdId, @Valid @RequestBody CreateAccountRequest request) {
        AccountView created = accounts.create(new Actor(principal.userId()), householdId, request.command());
        return ResponseEntity.created(URI.create("/api/v1/households/" + householdId
                + "/accounts/" + created.id() + "/")).body(created);
    }

    @GetMapping("/{accountId}/")
    public AccountView get(@AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable UUID householdId, @PathVariable UUID accountId) {
        return queries.get(new Actor(principal.userId()), householdId, accountId);
    }

    @PutMapping("/{accountId}/")
    public AccountView update(@AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable UUID householdId, @PathVariable UUID accountId,
            @Valid @RequestBody UpdateAccountRequest request) {
        return accounts.update(new Actor(principal.userId()), householdId, accountId, request.command());
    }
}
