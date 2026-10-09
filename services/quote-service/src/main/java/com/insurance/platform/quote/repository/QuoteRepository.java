package com.insurance.platform.quote.repository;

import com.insurance.platform.quote.entity.Quote;
import java.util.UUID;
import org.springframework.data.repository.Repository;

public interface QuoteRepository extends Repository<Quote, UUID> {
    Quote saveAndFlush(Quote quote);
}
