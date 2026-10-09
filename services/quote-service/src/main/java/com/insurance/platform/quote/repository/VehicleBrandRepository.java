package com.insurance.platform.quote.repository;

import com.insurance.platform.quote.entity.VehicleBrand;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

public interface VehicleBrandRepository extends Repository<VehicleBrand, UUID> {
    Optional<VehicleBrand> findById(UUID id);
}
