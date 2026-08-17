package com.example.comptable.dashboard.service;

import com.example.comptable.comptabilite.domain.StatutEcriture;
import com.example.comptable.comptabilite.repository.EcritureComptableRepository;
import com.example.comptable.comptabilite.repository.LigneEcritureRepository;
import com.example.comptable.comptabilite.web.dto.SoldeDto;
import com.example.comptable.dashboard.web.dto.TableauDeBordDto;
import com.example.comptable.document.domain.StatutDocument;
import com.example.comptable.document.domain.TypeDocument;
import com.example.comptable.document.repository.DocumentRepository;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.fiscalite.domain.StatutDeclaration;
import com.example.comptable.fiscalite.repository.DeclarationFiscaleRepository;
import com.example.comptable.fiscalite.web.dto.DeclarationFiscaleDto;
import com.example.comptable.security.UtilisateurPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/**
 * Agrege ce qui merite l'attention immediate du comptable sur une entreprise :
 * documents a comptabiliser, brouillons a valider, echeances fiscales proches ou
 * en retard, et un aperçu rapide de la tresorerie.
 */
@Service
@RequiredArgsConstructor
public class TableauDeBordService {

    private static final Set<TypeDocument> TYPES_COMPTABILISABLES =
            Set.of(TypeDocument.FACTURE_ACHAT, TypeDocument.FACTURE_VENTE);
    private static final int HORIZON_ECHEANCES_JOURS = 30;

    private final EntrepriseAccessService entrepriseAccessService;
    private final DocumentRepository documentRepository;
    private final EcritureComptableRepository ecritureComptableRepository;
    private final LigneEcritureRepository ligneEcritureRepository;
    private final DeclarationFiscaleRepository declarationFiscaleRepository;

    @Transactional(readOnly = true)
    public TableauDeBordDto generer(UUID entrepriseId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);

        LocalDate aujourdHui = LocalDate.now();

        long documentsATraiter = documentRepository.countByEntrepriseIdAndStatutAndTypeDocumentInAndEcritureIdIsNull(
                entrepriseId, StatutDocument.TRAITE, TYPES_COMPTABILISABLES);
        long documentsEnErreur = documentRepository.countByEntrepriseIdAndStatut(entrepriseId, StatutDocument.ERREUR);
        long ecrituresBrouillon =
                ecritureComptableRepository.countByEntrepriseIdAndStatut(entrepriseId, StatutEcriture.BROUILLON);

        BigDecimal netTresorerie = ligneEcritureRepository.soldeTresorerie(entrepriseId, aujourdHui);

        var prochainesEcheances = declarationFiscaleRepository
                .findByEntrepriseIdAndStatutNotAndDateEcheanceBetweenOrderByDateEcheanceAsc(
                        entrepriseId, StatutDeclaration.PAYEE, aujourdHui, aujourdHui.plusDays(HORIZON_ECHEANCES_JOURS))
                .stream().map(DeclarationFiscaleDto::from).toList();

        var echeancesEnRetard = declarationFiscaleRepository
                .findByEntrepriseIdAndStatutNotAndDateEcheanceBeforeOrderByDateEcheanceAsc(
                        entrepriseId, StatutDeclaration.PAYEE, aujourdHui)
                .stream().map(DeclarationFiscaleDto::from).toList();

        return new TableauDeBordDto(
                documentsATraiter,
                documentsEnErreur,
                ecrituresBrouillon,
                SoldeDto.depuisNet(netTresorerie),
                prochainesEcheances,
                echeancesEnRetard
        );
    }
}
