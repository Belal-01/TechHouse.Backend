package com.techhouse.shared.event;

import java.time.Instant;
import java.util.UUID;

public interface DomainEvent {

    UUID aggregateId();

    Instant occurredAt();
}
