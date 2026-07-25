package com.taimoor.inventory_service.repository;

import com.taimoor.inventory_service.model.Inventory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InventoryRepository extends JpaRepository<Inventory, Long> {
    Boolean existsBySkuCodeAndQuantityIsGreaterThanEqual(String skuCode,  Integer quantity);
}
