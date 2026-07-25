package br.com.ricarte.hookguard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "accounts")
public class Account {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 320)
    private String email;

    @Column(length = 200)
    private String name;

    @Column(name = "stripe_customer_id", length = 120)
    private String stripeCustomerId;

    @Column(nullable = false, length = 40)
    private String plan;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Account() {
    }

    public Account(UUID id, String email, String name, AccountPlan plan, Instant createdAt) {
        this.id = id;
        this.email = email;
        this.name = name;
        this.plan = plan.toStorage();
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getName() {
        return name;
    }

    public String getStripeCustomerId() {
        return stripeCustomerId;
    }

    public void setStripeCustomerId(String stripeCustomerId) {
        this.stripeCustomerId = stripeCustomerId;
    }

    public AccountPlan getPlan() {
        return AccountPlan.fromStorage(plan);
    }

    public void setPlan(AccountPlan plan) {
        this.plan = plan.toStorage();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
