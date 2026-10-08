package com.example.comptable.cabinet.workspace;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Contrat HTTP du Cabinet Workspace : le backend fournit tout (statuts, libellés, compteurs, priorités) ; le frontend ne recompose aucune règle métier. */
public final class CabinetWorkspaceDtos {

    private CabinetWorkspaceDtos() {
    }

    public record UtilisateurRef(UUID id, String nom) {
    }

    public record EtapeDto(String code, String libelle, boolean prete) {
    }

    public record EcheanceRef(UUID id, LocalDate date, String type, String libelle) {
    }

    public record DossierSummaryDto(UUID entrepriseId, String entrepriseNom, String identifiant, UUID exerciceId, LocalDate exerciceDebut, LocalDate exerciceFin, boolean courant,
                                    UtilisateurRef collaborateur, UtilisateurRef expert, String statut, String statutLibelle, String priorite,
                                    int etapesPretes, int etapesTotal, List<EtapeDto> etapes, long findingsActifs, long findingsBloquants, long piecesManquantes,
                                    long banqueExceptions, EcheanceRef prochaineEcheance, OffsetDateTime derniereActivite, String tva, String tvaLibelle) {
    }

    public record PageDto<T>(List<T> items, int page, int taille, long total) {
    }

    public record WorkItemDto(String kind, UUID id, UUID entrepriseId, String entrepriseNom, UUID exerciceId, String titre, String domaine, String impact, String priorite,
                              long ancienneteJours, OffsetDateTime depuis, UtilisateurRef responsable, String cible) {
    }

    public record EcheanceDto(UUID id, UUID entrepriseId, String entrepriseNom, UUID exerciceId, String type, String typeLibelle, String libelle, LocalDate date,
                              UtilisateurRef responsable, String statut, String periode) {
    }

    public record EcheancesDto(long enRetard, long aujourdhui, long cetteSemaine, long aVenir, List<EcheanceDto> items) {
    }

    public record SummaryDto(long dossiersActifs, long aTraiter, long enAttenteClient, long pretsPourRevue, long echeancesProches) {
    }

    public record DashboardDto(String perimetre, SummaryDto summary, PageDto<WorkItemDto> workQueue, List<DossierSummaryDto> reviewQueue, List<EcheanceDto> deadlines) {
    }

    public record DossierRef(UUID entrepriseId, UUID exerciceId) {
    }

    public record AffectationRequest(UUID collaborateurId, UUID expertId) {
    }

    public record AffectationEnMasseRequest(List<DossierRef> dossiers, UUID collaborateurId, UUID expertId) {
    }

    public record EcheanceRequest(UUID entrepriseId, UUID exerciceId, String type, String libelle, LocalDate date, UUID responsableId) {
    }

    public record EcheancePatch(LocalDate date, String statut, UUID responsableId, String libelle) {
    }
}
