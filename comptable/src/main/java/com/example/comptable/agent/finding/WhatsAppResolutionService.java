package com.example.comptable.agent.finding;

import com.example.comptable.agent.finding.domain.Finding;
import com.example.comptable.agent.finding.domain.TypeFinding;
import com.example.comptable.comptabilite.domain.LigneReleve;
import com.example.comptable.demande.repository.DemandePieceRepository;
import com.example.comptable.demande.service.DemandePieceService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Boucle de resolution WhatsApp (mission P0, section 13) : "probleme detecte -> preuve manquante
 * -> demande ciblee -> WhatsApp -> reponse -> ... -> probleme resolu". Point de decision Java
 * SEPARE du Missing Evidence Engine ({@link MissingEvidenceAnalyzer}, qui ne fait lui-meme que
 * produire un {@link Finding} + une action recommandee, jamais une demande - section 12, "ne pas
 * declencher automatiquement des demandes hors controle").
 * <p>
 * Portee volontairement etroite pour ce P0 : seul le cas le moins ambigu (aucun candidat trouve du
 * tout, {@code candidatsExamines == 0}) declenche une {@code DemandePiece} automatique - un client
 * qu'on sollicite pour une transaction qui a au moins des candidats plausibles (juste ambigus)
 * recevrait une demande deroutante ("de quoi parlez-vous ?"), ce cas reste une revue interne. Le
 * texte envoye au client cite le montant/la date/le libelle exacts (section 13) plutot qu'un
 * message generique "envoyez vos pieces manquantes" - {@code RelancePieceService}/
 * {@code RelanceScheduler} (inchanges) portent ensuite l'envoi WhatsApp reel.
 */
@Component
public class WhatsAppResolutionService {

    private final DemandePieceService demandePieceService;
    private final DemandePieceRepository demandePieceRepository;

    public WhatsAppResolutionService(DemandePieceService demandePieceService,
                                      DemandePieceRepository demandePieceRepository) {
        this.demandePieceService = demandePieceService;
        this.demandePieceRepository = demandePieceRepository;
    }

    @Transactional
    public void resoudreSiPreuveManquante(Finding finding, LigneReleve ligne) {
        if (finding.getType() != TypeFinding.MISSING_EVIDENCE) {
            return;
        }
        if (!aucunCandidatExamine(finding)) {
            return;
        }
        if (demandePieceRepository.existsByFindingId(finding.getId())) {
            return; // deja sollicite pour ce constat, ne pas re-solliciter a chaque passage
        }

        String libelle = "Justificatif du paiement de " + ligne.getMontant().abs() + " FCFA"
                + (ligne.getLibelle() != null && !ligne.getLibelle().isBlank() ? " (" + ligne.getLibelle() + ")" : "")
                + " du " + ligne.getDateOperation();
        String description = "Nous avons besoin du justificatif correspondant a cette transaction bancaire "
                + "pour la rapprocher de votre comptabilite.";

        demandePieceService.creerDepuisFinding(finding.getEntreprise().getId(), finding.getId(), libelle, description);
    }

    private boolean aucunCandidatExamine(Finding finding) {
        if (finding.getEvidenceJson() == null) {
            return false;
        }
        Object valeur = finding.getEvidenceJson().get("candidatsExamines");
        return valeur instanceof Number nombre && nombre.intValue() == 0;
    }
}
