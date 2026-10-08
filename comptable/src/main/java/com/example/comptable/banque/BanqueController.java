package com.example.comptable.banque;

import com.example.comptable.banque.BanqueDto.CandidatDto;
import com.example.comptable.banque.BanqueDto.LotImportDto;
import com.example.comptable.banque.BanqueDto.MetriquesDto;
import com.example.comptable.banque.BanqueDto.PageTransactions;
import com.example.comptable.banque.BanqueDto.PropositionLotDto;
import com.example.comptable.banque.BanqueDto.ResultatLot;
import com.example.comptable.banque.BanqueDto.StatutTransaction;
import com.example.comptable.banque.BanqueDto.SyntheseDto;
import com.example.comptable.comptabilite.allocation.AllocationDto;
import com.example.comptable.comptabilite.allocation.PaiementsQueryService;
import com.example.comptable.comptabilite.allocation.PaymentAllocation;
import com.example.comptable.comptabilite.allocation.PaymentAllocationService;
import com.example.comptable.comptabilite.allocation.SourceAllocation;
import com.example.comptable.comptabilite.service.RapprochementBancaireService;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.security.UtilisateurPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Ecran Banque : import (apercu puis confirmation), transactions et leur statut, rapprochements (confirmer, rejeter, choisir, annuler),
 * affectation des reglements aux factures, mesures. Reserve au cabinet. Le comptable designe toujours des objets par leur libelle (candidats
 * proposes), jamais par un identifiant saisi.
 */
@RestController
@RequestMapping("/api/entreprises/{entrepriseId}/banque")
@RequiredArgsConstructor
public class BanqueController {

    private final RapprochementBancaireService rapprochementBancaireService;
    private final BankStatementImporter importer;
    private final BankTransactionQueryService queryService;
    private final BankReconciliationService reconciliationService;
    private final InternalTransferService internalTransferService;
    private final BankImportBatchRepository batchRepository;
    private final PaymentAllocationService allocationService;
    private final PaiementsQueryService paiementsQueryService;
    private final EntrepriseAccessService entrepriseAccessService;
    private final com.example.comptable.comptabilite.repository.LigneReleveRepository ligneReleveRepository;

    public record ChoixRequest(@NotNull UUID ligneEcritureId) {
    }

    public record MotifRequest(String motif) {
    }

    public record ConfirmerLotRequest(@NotEmpty List<UUID> reconciliationIds, Integer confianceMin) {
    }

    public record AllouerRequest(@NotNull UUID paymentLineId, @NotNull UUID invoiceLineId, @NotNull @Positive BigDecimal montant) {
    }

    /** Colonnes du CSV si le comptable ne se fie pas a la detection (positions a partir de 0). */
    private BankStatementImporter.DemandeImport demande(UUID compteId, String libelle, LocalDate dateDebut, LocalDate dateFin, BigDecimal soldeOuverture,
                                                         BigDecimal soldeCloture, Integer colDate, Integer colDateValeur, Integer colLibelle,
                                                         Integer colReference, Integer colDebit, Integer colCredit, Integer colMontant, Integer colSolde,
                                                         Boolean enTete, String separateur) {
        boolean mappingExplicite = colDate != null || colLibelle != null || colMontant != null || colDebit != null || colCredit != null;
        BankCsvMapping mapping = mappingExplicite
                ? new BankCsvMapping(colDate, colDateValeur, colLibelle, colReference, colDebit, colCredit, colMontant, colSolde,
                        enTete == null || enTete, separateur == null || separateur.isBlank() ? null : separateur)
                : null;
        return new BankStatementImporter.DemandeImport(compteId, libelle, dateDebut, dateFin, soldeOuverture, soldeCloture, mapping);
    }

    // ------------------------------------------------------------------------------------------------ import

    @PostMapping("/imports/apercu")
    public BankStatementImporter.Apercu apercuImport(@PathVariable UUID entrepriseId, @RequestParam UUID compteId, @RequestParam MultipartFile fichier,
                                                    @RequestParam(required = false) BigDecimal soldeOuverture, @RequestParam(required = false) BigDecimal soldeCloture,
                                                    @RequestParam(required = false) Integer colDate, @RequestParam(required = false) Integer colDateValeur,
                                                    @RequestParam(required = false) Integer colLibelle, @RequestParam(required = false) Integer colReference,
                                                    @RequestParam(required = false) Integer colDebit, @RequestParam(required = false) Integer colCredit,
                                                    @RequestParam(required = false) Integer colMontant, @RequestParam(required = false) Integer colSolde,
                                                    @RequestParam(required = false) Boolean enTete, @RequestParam(required = false) String separateur,
                                                    @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return importer.apercu(entrepriseId, demande(compteId, null, null, null, soldeOuverture, soldeCloture, colDate, colDateValeur, colLibelle,
                colReference, colDebit, colCredit, colMontant, colSolde, enTete, separateur), fichier, principal);
    }

    @PostMapping("/imports")
    public ResponseEntity<RapprochementBancaireService.ResultatImport> importerReleve(
            @PathVariable UUID entrepriseId, @RequestParam UUID compteId, @RequestParam MultipartFile fichier,
            @RequestParam(required = false) String libelle,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateDebut,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFin,
            @RequestParam(required = false) BigDecimal soldeOuverture, @RequestParam(required = false) BigDecimal soldeCloture,
            @RequestParam(required = false) Integer colDate, @RequestParam(required = false) Integer colDateValeur,
            @RequestParam(required = false) Integer colLibelle, @RequestParam(required = false) Integer colReference,
            @RequestParam(required = false) Integer colDebit, @RequestParam(required = false) Integer colCredit,
            @RequestParam(required = false) Integer colMontant, @RequestParam(required = false) Integer colSolde,
            @RequestParam(required = false) Boolean enTete, @RequestParam(required = false) String separateur,
            @AuthenticationPrincipal UtilisateurPrincipal principal) {
        RapprochementBancaireService.ResultatImport resultat = rapprochementBancaireService.importerComplet(entrepriseId,
                demande(compteId, libelle, dateDebut, dateFin, soldeOuverture, soldeCloture, colDate, colDateValeur, colLibelle, colReference, colDebit,
                        colCredit, colMontant, colSolde, enTete, separateur), fichier, principal);
        return ResponseEntity.status(resultat.releve() != null ? HttpStatus.CREATED : HttpStatus.OK).body(resultat);
    }

    @GetMapping("/imports")
    public List<LotImportDto> lots(@PathVariable UUID entrepriseId, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        return batchRepository.findByEntrepriseIdOrderByImportedAtDesc(entrepriseId).stream()
                .map(b -> new LotImportDto(b.getId(), b.getFileName(), b.getImportedAt(), b.getTotalRows(), b.getValidRows(), b.getRejectedRows(),
                        b.getExactDuplicates(), b.getPossibleDuplicates(), b.getImportedRows(), b.getStatus()))
                .toList();
    }

    // ------------------------------------------------------------------------------------------------ transactions

    @GetMapping("/synthese")
    public SyntheseDto synthese(@PathVariable UUID entrepriseId, @RequestParam(required = false) UUID compteId, @RequestParam(required = false) String periode,
                                @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return queryService.synthese(entrepriseId, compteId, periode, principal);
    }

    @GetMapping("/metriques")
    public MetriquesDto metriques(@PathVariable UUID entrepriseId, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return queryService.metriques(entrepriseId, principal);
    }

    @GetMapping("/transactions")
    public PageTransactions transactions(@PathVariable UUID entrepriseId, @RequestParam(required = false) UUID compteId,
                                         @RequestParam(required = false) String periode, @RequestParam(required = false) StatutTransaction statut,
                                         @RequestParam(required = false) String q, @RequestParam(required = false) String tri,
                                         @RequestParam(defaultValue = "desc") String ordre, @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "25") int taille, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return queryService.lister(entrepriseId, compteId, periode, statut, q, tri, !"asc".equalsIgnoreCase(ordre), page, taille, principal);
    }

    @GetMapping("/transactions/{transactionId}/candidats")
    public List<CandidatDto> candidats(@PathVariable UUID entrepriseId, @PathVariable UUID transactionId, @RequestParam(required = false) String q,
                                       @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return queryService.candidats(entrepriseId, transactionId, q, principal);
    }

    /** Choix du comptable parmi les candidats (ou confirmation de la proposition affichee). */
    @PostMapping("/transactions/{transactionId}/rapprocher")
    public BankReconciliation rapprocher(@PathVariable UUID entrepriseId, @PathVariable UUID transactionId, @Valid @RequestBody ChoixRequest requete,
                                         @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return reconciliationService.pointer(entrepriseId, transactionId, requete.ligneEcritureId(), SourceReconciliation.USER, principal);
    }

    @PostMapping("/transactions/{transactionId}/annuler-rapprochement")
    public ResponseEntity<BankReconciliation> annuler(@PathVariable UUID entrepriseId, @PathVariable UUID transactionId, @RequestBody MotifRequest requete,
                                                      @AuthenticationPrincipal UtilisateurPrincipal principal) {
        BankReconciliation r = reconciliationService.annuler(entrepriseId, transactionId, requete.motif(), principal);
        return r == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(r);
    }

    /** « Ce ne sont pas deux fois la meme operation » : la transaction n'est plus suspecte de doublon. */
    @PostMapping("/transactions/{transactionId}/doublon-distinct")
    public ResponseEntity<Void> doublonDistinct(@PathVariable UUID entrepriseId, @PathVariable UUID transactionId,
                                                @AuthenticationPrincipal UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        var transaction = ligneReleveRepository.findById(transactionId).filter(l -> l.getEntrepriseId().equals(entrepriseId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Transaction bancaire introuvable"));
        transaction.setDuplicateStatus("DISTINCT_CONFIRMED");
        ligneReleveRepository.save(transaction);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------------------------------------ rapprochements

    @PostMapping("/rapprochements/{reconciliationId}/confirmer")
    public BankReconciliation confirmer(@PathVariable UUID entrepriseId, @PathVariable UUID reconciliationId,
                                        @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return reconciliationService.confirmer(entrepriseId, reconciliationId, principal);
    }

    @PostMapping("/rapprochements/{reconciliationId}/rejeter")
    public BankReconciliation rejeter(@PathVariable UUID entrepriseId, @PathVariable UUID reconciliationId, @RequestBody(required = false) MotifRequest requete,
                                      @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return reconciliationService.rejeter(entrepriseId, reconciliationId, requete != null ? requete.motif() : null, principal);
    }

    /** Apercu OBLIGATOIRE avant validation en masse : rien n'est valide sans filtre de confiance. */
    @GetMapping("/rapprochements/lot/apercu")
    public List<PropositionLotDto> apercuLot(@PathVariable UUID entrepriseId, @RequestParam(defaultValue = "85") int confianceMin,
                                             @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return queryService.apercuLot(entrepriseId, Math.max(confianceMin, 50), principal);
    }

    /** Valide les propositions designees ; chacune est revalidee cote serveur (statut, confiance), jamais « tout valider ». */
    @PostMapping("/rapprochements/lot/confirmer")
    public ResultatLot confirmerLot(@PathVariable UUID entrepriseId, @Valid @RequestBody ConfirmerLotRequest requete,
                                    @AuthenticationPrincipal UtilisateurPrincipal principal) {
        int seuil = Math.max(requete.confianceMin() != null ? requete.confianceMin() : 85, 50);
        List<UUID> eligibles = queryService.apercuLot(entrepriseId, seuil, principal).stream().map(PropositionLotDto::reconciliationId).toList();
        int confirmees = 0;
        List<String> motifs = new ArrayList<>();
        for (UUID id : requete.reconciliationIds()) {
            if (!eligibles.contains(id)) {
                motifs.add(id + " : proposition absente ou sous le seuil de confiance (" + seuil + ")");
                continue;
            }
            try {
                reconciliationService.confirmer(entrepriseId, id, principal);
                confirmees++;
            } catch (ResponseStatusException e) {
                motifs.add(id + " : " + e.getReason());
            }
        }
        return new ResultatLot(confirmees, requete.reconciliationIds().size() - confirmees, motifs);
    }

    // ------------------------------------------------------------------------------------------------ virements internes

    /** Confirme que deux transactions de deux comptes de l'entreprise sont un virement interne (ni charge, ni produit, ni reglement de tiers). */
    @PostMapping("/virements-internes/{transfertId}/confirmer")
    public BankInternalTransfer confirmerVirement(@PathVariable UUID entrepriseId, @PathVariable UUID transfertId, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return internalTransferService.confirmer(entrepriseId, transfertId, principal);
    }

    @PostMapping("/virements-internes/{transfertId}/rejeter")
    public BankInternalTransfer rejeterVirement(@PathVariable UUID entrepriseId, @PathVariable UUID transfertId, @RequestBody(required = false) MotifRequest requete,
                                                @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return internalTransferService.rejeter(entrepriseId, transfertId, requete != null ? requete.motif() : null, principal);
    }

    @PostMapping("/virements-internes/{transfertId}/annuler")
    public BankInternalTransfer annulerVirement(@PathVariable UUID entrepriseId, @PathVariable UUID transfertId, @RequestBody MotifRequest requete,
                                                @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return internalTransferService.annuler(entrepriseId, transfertId, requete.motif(), principal);
    }

    // ------------------------------------------------------------------------------------------------ paiements

    @GetMapping("/paiements/reglements-a-repartir")
    public List<AllocationDto.ReglementARepartir> reglementsARepartir(@PathVariable UUID entrepriseId, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return paiementsQueryService.reglementsARepartir(entrepriseId, principal);
    }

    @GetMapping("/paiements/reglements/{paymentLineId}/factures-candidates")
    public List<AllocationDto.FactureCandidate> facturesCandidates(@PathVariable UUID entrepriseId, @PathVariable UUID paymentLineId,
                                                                  @RequestParam(required = false) String q, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return paiementsQueryService.facturesCandidates(entrepriseId, paymentLineId, q, principal);
    }

    @GetMapping("/paiements/factures")
    public List<AllocationDto.FactureCandidate> facturesOuvertes(@PathVariable UUID entrepriseId, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return paiementsQueryService.facturesOuvertes(entrepriseId, principal);
    }

    @GetMapping("/paiements/allocations")
    public List<AllocationDto.Allocation> allocations(@PathVariable UUID entrepriseId, @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return paiementsQueryService.allocations(entrepriseId, principal);
    }

    @PostMapping("/paiements/allocations")
    public ResponseEntity<PaymentAllocation> allouer(@PathVariable UUID entrepriseId, @Valid @RequestBody AllouerRequest requete,
                                                     @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED).body(allocationService.allouer(entrepriseId, requete.paymentLineId(), requete.invoiceLineId(),
                requete.montant(), SourceAllocation.USER, principal));
    }

    @PostMapping("/paiements/allocations/{allocationId}/annuler")
    public PaymentAllocation annulerAllocation(@PathVariable UUID entrepriseId, @PathVariable UUID allocationId, @RequestBody MotifRequest requete,
                                               @AuthenticationPrincipal UtilisateurPrincipal principal) {
        return allocationService.annuler(entrepriseId, allocationId, requete.motif(), principal);
    }
}
