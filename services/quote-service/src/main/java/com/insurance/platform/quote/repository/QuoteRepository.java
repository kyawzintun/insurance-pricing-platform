package com.insurance.platform.quote.repository;

import com.insurance.platform.quote.entity.Quote;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface QuoteRepository extends Repository<Quote, UUID> {
    Quote saveAndFlush(Quote quote);

    @EntityGraph(attributePaths = {"driver", "vehicle", "pricing", "pricing.adjustments"})
    Optional<Quote> findByIdAndCustomerId(UUID id, UUID customerId);

    @EntityGraph(attributePaths = {"driver", "vehicle", "pricing", "pricing.adjustments"})
    Optional<Quote> findById(UUID id);

    // Page scalar IDs only: fetching a collection in this query would break SQL pagination.
    @Query(value = "select q.id from Quote q where q.customerId = :customerId order by q.createdAt desc, q.id desc",
           countQuery = "select count(q) from Quote q where q.customerId = :customerId")
    Page<UUID> findIdsByCustomerId(@Param("customerId") UUID customerId, Pageable pageable);

    @Query(value = "select q.id from Quote q order by q.createdAt desc, q.id desc",
           countQuery = "select count(q) from Quote q")
    Page<UUID> findIds(Pageable pageable);

    @EntityGraph(attributePaths = {"driver", "vehicle", "pricing", "pricing.adjustments"})
    @Query("select distinct q from Quote q where q.id in :ids and q.customerId = :customerId")
    List<Quote> findSnapshotsByIdsAndCustomerId(@Param("ids") List<UUID> ids, @Param("customerId") UUID customerId);

    @EntityGraph(attributePaths = {"driver", "vehicle", "pricing", "pricing.adjustments"})
    @Query("select distinct q from Quote q where q.id in :ids")
    List<Quote> findSnapshotsByIds(@Param("ids") List<UUID> ids);
}
