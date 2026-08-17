package com.example.comptable.common.domain;

import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@MappedSuperclass
@Getter
@Setter
public abstract class UuidEntity {

    @Id
    private UUID id;

    @PrePersist
    protected void ensureId() {
        if (id == null) {
            id = UUID.randomUUID();
        }
    }
}
