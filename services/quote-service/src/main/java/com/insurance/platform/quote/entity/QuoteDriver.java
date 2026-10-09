package com.insurance.platform.quote.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "quote_drivers")
public class QuoteDriver {
    @Id
    private UUID id;
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "quote_id", nullable = false, unique = true)
    private Quote quote;
    private LocalDate dateOfBirth;
    private int drivingExperienceYears;
    private int previousClaimsCount;

    protected QuoteDriver() {}

    public QuoteDriver(
            UUID id, Quote quote, LocalDate dateOfBirth,
            int drivingExperienceYears, int previousClaimsCount) {
        this.id = id;
        this.quote = quote;
        this.dateOfBirth = dateOfBirth;
        this.drivingExperienceYears = drivingExperienceYears;
        this.previousClaimsCount = previousClaimsCount;
    }

    public UUID getId() { return id; }
    public Quote getQuote() { return quote; }
    public LocalDate getDateOfBirth() { return dateOfBirth; }
    public int getDrivingExperienceYears() { return drivingExperienceYears; }
    public int getPreviousClaimsCount() { return previousClaimsCount; }
}
