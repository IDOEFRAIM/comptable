package com.example.comptable.comptabilite.lineage;

import com.example.comptable.comptabilite.domain.EcritureComptable;
import com.example.comptable.comptabilite.operation.common.domain.AccountingOperationProposal;
import com.example.comptable.comptabilite.operation.common.repository.AccountingOperationProposalRepository;
import com.example.comptable.comptabilite.repository.EcritureComptableRepository;
import com.example.comptable.document.domain.Document;
import com.example.comptable.document.repository.DocumentRepository;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.security.UtilisateurPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * Navigation bidirectionnelle sur la chaine « cause metier -> proposition -> ecriture » (Phase B, B0).
 * Lecture seule, bornee a l'entreprise, fondee uniquement sur des liens persistes.
 */
@Service
@RequiredArgsConstructor
public class AccountingLineageService {

    private final EntrepriseAccessService entrepriseAccessService;
    private final EcritureComptableRepository ecritureComptableRepository;
    private final AccountingOperationProposalRepository proposalRepository;
    private final DocumentRepository documentRepository;

    @Transactional(readOnly = true)
    public AccountingLineageDto.DepuisEcriture depuisEcriture(UUID entrepriseId, UUID ecritureId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        EcritureComptable ecriture = ecritureComptableRepository.findById(ecritureId)
                .filter(e -> e.getEntreprise().getId().equals(entrepriseId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ecriture introuvable"));

        List<AccountingOperationProposal> propositions = proposalRepository.findByEcritureId(ecritureId).stream()
                .filter(p -> p.getEntreprise().getId().equals(entrepriseId))
                .toList();
        AccountingLineageDto.Proposition proposition = propositions.isEmpty() ? null : versDto(propositions.get(0));

        List<AccountingLineageDto.DocumentOrigine> documents = new java.util.ArrayList<>();
        documentRepository.findByEcritureId(ecritureId).filter(d -> d.getEntreprise().getId().equals(entrepriseId))
                .ifPresent(d -> documents.add(versDocument(d, "DIRECT")));
        documentRepository.findViaFactureFournisseur(entrepriseId, ecritureId)
                .forEach(d -> documents.add(versDocument(d, "VIA_FACTURE")));
        AccountingLineageDto.DocumentOrigine direct = documents.stream().filter(d -> "DIRECT".equals(d.lien())).findFirst().orElse(null);

        return new AccountingLineageDto.DepuisEcriture(ecriture.getId(), ecriture.getNumeroPiece(), ecriture.getStatut().name(),
                proposition != null ? "MOTEUR" : "SAISIE_MANUELLE", proposition, direct, documents);
    }

    @Transactional(readOnly = true)
    public AccountingLineageDto.DepuisSource depuisSource(UUID entrepriseId, String sourceType, UUID sourceId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        List<AccountingLineageDto.Proposition> consequences = proposalRepository
                .findByEntrepriseIdAndSourceTypeAndSourceIdOrderByCreatedAtAsc(entrepriseId, sourceType, sourceId).stream()
                .map(this::versDto)
                .toList();
        List<AccountingLineageDto.DocumentOrigine> documents = new java.util.ArrayList<>();
        documentRepository.findByEntrepriseIdAndCibleTypeAndCibleId(entrepriseId, sourceType, sourceId)
                .forEach(d -> documents.add(versDocument(d, "RAPPROCHEE")));
        if ("DOCUMENT".equals(sourceType)) {
            documentRepository.findByIdAndEntrepriseId(sourceId, entrepriseId).ifPresent(d -> documents.add(versDocument(d, "DIRECT")));
        }
        return new AccountingLineageDto.DepuisSource(sourceType, sourceId, consequences, documents);
    }

    /** Document -> facture visee -> propositions de cette facture (+ celles issues directement de la piece) -> ecritures. */
    @Transactional(readOnly = true)
    public AccountingLineageDto.DepuisDocument depuisDocument(UUID entrepriseId, UUID documentId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        Document document = documentRepository.findByIdAndEntrepriseId(documentId, entrepriseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document introuvable"));
        List<AccountingLineageDto.Proposition> consequences = new java.util.ArrayList<>();
        if (document.getCibleId() != null) {
            proposalRepository.findByEntrepriseIdAndSourceTypeAndSourceIdOrderByCreatedAtAsc(entrepriseId, document.getCibleType(), document.getCibleId())
                    .forEach(p -> consequences.add(versDto(p)));
        }
        proposalRepository.findByEntrepriseIdAndSourceTypeAndSourceIdOrderByCreatedAtAsc(entrepriseId, "DOCUMENT", documentId)
                .forEach(p -> consequences.add(versDto(p)));
        return new AccountingLineageDto.DepuisDocument(document.getId(), document.getNomFichierOriginal(), document.getTypeDocument().name(),
                document.getStatutRapprochement().name(),
                document.getCibleId() != null ? new AccountingLineageDto.CibleFacture(document.getCibleType(), document.getCibleId()) : null,
                document.getEcritureId(), consequences);
    }

    private AccountingLineageDto.DocumentOrigine versDocument(Document d, String lien) {
        return new AccountingLineageDto.DocumentOrigine(d.getId(), d.getNomFichierOriginal(), d.getTypeDocument().name(), lien);
    }

    private AccountingLineageDto.Proposition versDto(AccountingOperationProposal p) {
        return new AccountingLineageDto.Proposition(p.getId(), p.getType().name(), p.getStatut().name(),
                new AccountingLineageDto.Source(p.getSourceType(), p.getSourceId(), p.getSourceEvent()),
                p.getEcriture() != null ? p.getEcriture().getId() : null);
    }
}
