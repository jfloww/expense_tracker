package com.expensetracker.households.domain;

import com.expensetracker.identity.domain.User;
import com.expensetracker.shared.error.DomainException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Entity
@Table(name = "memberships", uniqueConstraints = @UniqueConstraint(columnNames = {"household_id", "user_id"}))
public class Membership {
    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "household_id", nullable = false, updatable = false)
    private Household household;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Role role;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Membership() {}

    private Membership(Household household, User user, Role role) {
        if (household == null || user == null || role == null) {
            throw DomainException.invalid("invalid_membership");
        }
        this.id = UUID.randomUUID();
        this.household = household;
        this.user = user;
        this.role = role;
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public static Membership create(Household household, User user, Role role) {
        return new Membership(household, user, role);
    }

    /** Called only by the authorized membership application service. */
    public void changeRole(Role role) {
        if (role == null) {
            throw DomainException.invalid("invalid_role");
        }
        this.role = role;
    }

    public UUID getId() { return id; }
    public Household getHousehold() { return household; }
    public User getUser() { return user; }
    public Role getRole() { return role; }
    public Instant getCreatedAt() { return createdAt; }
}
