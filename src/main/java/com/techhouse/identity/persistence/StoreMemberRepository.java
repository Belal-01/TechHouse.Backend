package com.techhouse.identity.persistence;

import com.techhouse.identity.domain.StoreMember;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StoreMemberRepository extends JpaRepository<StoreMember, UUID> {

    List<StoreMember> findByUserId(UUID userId);

    Optional<StoreMember> findByStoreIdAndUserId(UUID storeId, UUID userId);
}
