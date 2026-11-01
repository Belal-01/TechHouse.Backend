package com.techhouse.identity.persistence;

import com.techhouse.identity.domain.Store;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface StoreRepository extends JpaRepository<Store, UUID> {

    List<Store> findByActiveTrue();
}
