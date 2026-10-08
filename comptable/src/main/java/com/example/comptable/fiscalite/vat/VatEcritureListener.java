package com.example.comptable.fiscalite.vat;

import com.example.comptable.comptabilite.domain.EcritureComptable;
import com.example.comptable.comptabilite.domain.StatutEcriture;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PostUpdate;
import org.springframework.stereotype.Component;

/** Une écriture validée créée ou modifiée peut changer la TVA d'une période : signalée au {@link VatChangeTracker} (coalescé, traité après le commit). */
@Component
public class VatEcritureListener {

    private final VatChangeTracker tracker;

    public VatEcritureListener(VatChangeTracker tracker) {
        this.tracker = tracker;
    }

    @PostPersist
    @PostUpdate
    void apresEcriture(EcritureComptable e) {
        if (e.getStatut() == StatutEcriture.VALIDEE && e.getEntreprise() != null) {
            tracker.marquer(e.getEntreprise().getId(), e.getDateEcriture());
        }
    }
}
