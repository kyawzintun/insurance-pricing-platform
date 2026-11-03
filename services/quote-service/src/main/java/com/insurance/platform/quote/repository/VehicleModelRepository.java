package com.insurance.platform.quote.repository;

import com.insurance.platform.quote.entity.VehicleModel;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

public interface VehicleModelRepository extends Repository<VehicleModel, UUID> {
    Optional<VehicleModel> findById(UUID id);
    List<VehicleModel> findByBrandIdAndActiveTrueOrderByNameAscIdAsc(UUID brandId);
}
