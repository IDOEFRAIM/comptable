package com.example.comptable.etatsfinanciers.sync;

import com.example.comptable.etatsfinanciers.notes.FinancialStatementNote;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PostUpdate;
import org.springframework.stereotype.Component;

import java.util.EnumSet;

/** Une note créée ou modifiée (données manuelles, applicabilité, revue) change les constats de notes de son exercice. Les écritures de la synchronisation elle-même sont ignorées par le tracker. */
@Component
public class NoteControlListener {

    private final FinancialControlChangeTracker tracker;

    public NoteControlListener(FinancialControlChangeTracker tracker) {
        this.tracker = tracker;
    }

    @PostPersist
    @PostUpdate
    void apresNote(FinancialStatementNote n) {
        tracker.marquer(n.getEntrepriseId(), n.getExerciceId(), EnumSet.of(FinancialControlDomain.NOTES));
    }
}
