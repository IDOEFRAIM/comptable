package com.example.comptable.agent.matching;

import com.example.comptable.agent.matching.dto.HistoricalPaymentDTO;
import com.example.comptable.agent.matching.dto.MatchingCandidateDTO;
import com.example.comptable.agent.matching.dto.MatchingContextDTO;
import com.example.comptable.agent.matching.dto.MatchingRequestDTO;
import com.example.comptable.agent.matching.dto.MatchingSourceDTO;
import com.example.comptable.agent.matching.domain.SourceType;
import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.Facture;
import com.example.comptable.comptabilite.domain.LigneEcriture;
import com.example.comptable.comptabilite.domain.LigneReleve;
import com.example.comptable.comptabilite.domain.StatutFacture;
import com.example.comptable.comptabilite.domain.Tiers;
import com.example.comptable.comptabilite.domain.TypeFacture;
import com.example.comptable.comptabilite.repository.FactureRepository;
import com.example.comptable.comptabilite.repository.LigneEcritureRepository;
import com.example.comptable.comptabilite.repository.TiersRepository;
import com.example.comptable.document.domain.Document;
import com.example.comptable.document.domain.TypeDocument;
import com.example.comptable.document.repository.DocumentRepository;
import com.example.comptable.entreprise.domain.Entreprise;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Construit le {@link MatchingRequestDTO} envoye a ai-service pour une ligne de releve bancaire
 * non rapprochee : source + candidats multi-source (ecritures, factures clients, pieces
 * fournisseurs/documents) + contexte (tiers probable, historique court). Recherche progressive
 * (section 4 de la mission) : une fenetre de dates etroite d'abord, elargie seulement si aucun
 * candidat n'a ete trouve - jamais de recherche "toutes les factures de l'entreprise".
 * <p>
 * Simplification assumee par rapport au schema a 4 niveaux de la mission (montant+date,
 * montant+tiers, reference/libelle, elargissement) : la fenetre de dates est le seul niveau
 * de recherche SQL (2 paliers, etroit puis large) ; le scoring fin par montant/tiers/reference
 * est deja fait cote ai-service (matching_scoring.py) sur l'ensemble ainsi borne - dupliquer ce
 * scoring ici irait a l'encontre de la consigne "ne pas dupliquer la logique de scoring Python
 * cote Java" (section 5).
 */
@Component
@RequiredArgsConstructor
public class MatchingContextBuilder {

    private static final int FENETRE_JOURS_ETROITE = 7;
    private static final int FENETRE_JOURS_LARGE = 30;
    private static final int LIMITE_CANDIDATS = 15;
    private static final int LIMITE_HISTORIQUE = 5;
    private static final int HISTORIQUE_MOIS = 12;

    private final LigneEcritureRepository ligneEcritureRepository;
    private final FactureRepository factureRepository;
    private final DocumentRepository documentRepository;
    private final TiersRepository tiersRepository;
    private final com.example.comptable.comptabilite.allocation.StatutPaiementFactureService statutPaiementFactureService;

    public MatchingRequestDTO construire(Entreprise entreprise, CompteComptable compte, LigneReleve ligne,
                                          Set<UUID> ecrituresDejaUtilisees) {
        MatchingSourceDTO source = construireSource(ligne);

        List<MatchingCandidateDTO> candidats = rechercherCandidats(entreprise, compte, ligne,
                ecrituresDejaUtilisees, FENETRE_JOURS_ETROITE);
        if (candidats.isEmpty()) {
            candidats = rechercherCandidats(entreprise, compte, ligne, ecrituresDejaUtilisees, FENETRE_JOURS_LARGE);
        }
        if (candidats.size() > LIMITE_CANDIDATS) {
            candidats = candidats.subList(0, LIMITE_CANDIDATS);
        }

        MatchingContextDTO contexte = construireContexte(entreprise, ligne);
        return new MatchingRequestDTO(source, candidats, contexte);
    }

    private MatchingSourceDTO construireSource(LigneReleve ligne) {
        return new MatchingSourceDTO(
                SourceType.BANK_TRANSACTION.name(),
                ligne.getId().toString(),
                ligne.getDateOperation(),
                ligne.getMontant(),
                "XOF",
                ligne.getLibelle(),
                null,
                null
        );
    }

    private List<MatchingCandidateDTO> rechercherCandidats(Entreprise entreprise, CompteComptable compte,
                                                             LigneReleve ligne, Set<UUID> dejaUtilisees,
                                                             int fenetreJours) {
        LocalDate debut = ligne.getDateOperation().minusDays(fenetreJours);
        LocalDate fin = ligne.getDateOperation().plusDays(fenetreJours);

        List<MatchingCandidateDTO> candidats = new ArrayList<>(candidatsEcritures(compte, debut, fin, dejaUtilisees));

        // Sens du mouvement : positif = argent recu (probablement un client qui paie une facture
        // de vente), negatif = argent sorti (probablement le paiement d'une piece fournisseur) -
        // evite d'interroger des sources structurellement non pertinentes (section 3B/16).
        if (ligne.getMontant().signum() > 0) {
            candidats.addAll(candidatsFacturesClients(entreprise, debut, fin));
        } else {
            candidats.addAll(candidatsDocuments(entreprise, debut, fin));
        }
        return candidats;
    }

    private List<MatchingCandidateDTO> candidatsEcritures(CompteComptable compte, LocalDate debut, LocalDate fin,
                                                            Set<UUID> dejaUtilisees) {
        return ligneEcritureRepository.rechercherMouvementsValides(compte.getId(), debut, fin).stream()
                .filter(le -> !dejaUtilisees.contains(le.getId()))
                .map(this::versCandidatEcriture)
                .toList();
    }

    private MatchingCandidateDTO versCandidatEcriture(LigneEcriture ligne) {
        var montant = ligne.getDebit().signum() > 0 ? ligne.getDebit() : ligne.getCredit().negate();
        String tiers = ligne.getTiers() != null ? ligne.getTiers().getNom() : null;
        return new MatchingCandidateDTO(
                SourceType.ACCOUNTING_ENTRY.name(), ligne.getId().toString(), ligne.getEcriture().getDateEcriture(),
                montant, "XOF", ligne.getLibelle(), null, tiers, null
        );
    }

    /** Uniquement les factures de vente (TypeFacture.FACTURE) au statut VALIDEE - "non encore
     * payees" au sens SaaS (une facture VALIDEE a deja genere son ecriture de vente, cf.
     * FacturationService ; le rapprochement propose ici sert a identifier QUELLE facture ce
     * paiement solde, jamais a en creer une nouvelle ecriture). */
    private List<MatchingCandidateDTO> candidatsFacturesClients(Entreprise entreprise, LocalDate debut, LocalDate fin) {
        return factureRepository
                .rechercherCandidatsRapprochement(entreprise.getId(), TypeFacture.FACTURE, StatutFacture.VALIDEE, debut, fin)
                .stream()
                .map(this::versCandidatFacture)
                .toList();
    }

    private MatchingCandidateDTO versCandidatFacture(Facture facture) {
        return new MatchingCandidateDTO(
                SourceType.CUSTOMER_INVOICE.name(), facture.getId().toString(), facture.getDateEmission(),
                facture.montantTtc(), "XOF", facture.getObjet(), facture.getNumero(),
                facture.getTiers().getNom(), facture.getStatut().name()
        );
    }

    /** Le SaaS n'a pas d'entite "facture fournisseur" dediee (cf. audit de mission) : une piece
     * fournisseur arrive comme {@link Document} (typeDocument=FACTURE_ACHAT), extraite par OCR,
     * pas encore liee a une ecriture ({@code ecritureId is null}). Etiquetee SUPPLIER_INVOICE
     * pour ce type precis, DOCUMENT pour les autres (RECU, AUTRE...) - meme table, semantique
     * differente cote prompt ai-service. */
    private List<MatchingCandidateDTO> candidatsDocuments(Entreprise entreprise, LocalDate debut, LocalDate fin) {
        return documentRepository.rechercherCandidatsRapprochement(entreprise.getId(), debut, fin).stream()
                .map(this::versCandidatDocument)
                .toList();
    }

    private MatchingCandidateDTO versCandidatDocument(Document document) {
        String type = document.getTypeDocument() == TypeDocument.FACTURE_ACHAT
                ? SourceType.SUPPLIER_INVOICE.name() : SourceType.DOCUMENT.name();
        String fournisseur = extraireFournisseur(document);
        return new MatchingCandidateDTO(
                type, document.getId().toString(), document.getDatePiece(), document.getMontantTtc(), "XOF",
                document.getNomFichierOriginal(), null, fournisseur, document.getStatut().name()
        );
    }

    private String extraireFournisseur(Document document) {
        if (document.getDonneesOcr() == null) {
            return null;
        }
        Object valeur = document.getDonneesOcr().get("fournisseur");
        return valeur != null ? String.valueOf(valeur) : null;
    }

    /**
     * Contexte enrichi (section 3D/3E) : tente d'identifier a quel {@link Tiers} connu le
     * libelle brut correspond probablement (comparaison textuelle simple, cote Java - la
     * similarite fine reste au LLM cote ai-service), pour en tirer un petit historique de
     * paiements passes. {@link TiersRepository#findByEntrepriseId} reste borne (le nombre de
     * tiers d'un cabinet est naturellement petit, contrairement au volume de transactions).
     */
    private MatchingContextDTO construireContexte(Entreprise entreprise, LigneReleve ligne) {
        Optional<Tiers> tiersProbable = resoudreTiersProbable(entreprise, ligne.getLibelle());
        List<HistoricalPaymentDTO> historique = tiersProbable
                .map(tiers -> construireHistorique(entreprise, tiers))
                .orElse(List.of());
        return new MatchingContextDTO(null, "SYSCOHADA", entreprise.getId().toString(), historique);
    }

    private Optional<Tiers> resoudreTiersProbable(Entreprise entreprise, String libelle) {
        if (libelle == null || libelle.isBlank()) {
            return Optional.empty();
        }
        String libelleNormalise = normaliser(libelle);
        return tiersRepository.findByEntrepriseId(entreprise.getId()).stream()
                .filter(tiers -> !normaliser(tiers.getNom()).isBlank())
                .filter(tiers -> libelleNormalise.contains(normaliser(tiers.getNom())))
                .findFirst();
    }

    private String normaliser(String valeur) {
        return valeur == null ? "" : valeur.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    /** D'abord les factures de vente deja PAYEE pour ce tiers (paiements confirmes) ; a defaut,
     * les pieces fournisseur deja rattachees a une ecriture pour ce nom de fournisseur
     * (reutilise {@link DocumentRepository#rechercherParFournisseur}, deja borne a 5 - meme
     * requete que l'outil consulter_historique_fournisseur de l'agent comptable autonome). */
    private List<HistoricalPaymentDTO> construireHistorique(Entreprise entreprise, Tiers tiers) {
        // « Payee » = reste a payer nul, calcule depuis les allocations (le drapeau PAYEE n'existe plus).
        List<Facture> facturesPayees = statutPaiementFactureService.payees(entreprise.getId(), factureRepository
                        .rechercherCandidatsRapprochement(entreprise.getId(), TypeFacture.FACTURE, StatutFacture.VALIDEE,
                                LocalDate.now().minusMonths(HISTORIQUE_MOIS), LocalDate.now())
                        .stream()
                        .filter(facture -> facture.getTiers().getId().equals(tiers.getId()))
                        .toList())
                .stream()
                .limit(LIMITE_HISTORIQUE)
                .toList();
        if (!facturesPayees.isEmpty()) {
            return facturesPayees.stream()
                    .map(f -> new HistoricalPaymentDTO(f.montantTtc(), f.getDateEmission(), "FACTURE"))
                    .toList();
        }

        return documentRepository.rechercherParFournisseur(entreprise.getId(), tiers.getNom(), LIMITE_HISTORIQUE).stream()
                .map(d -> new HistoricalPaymentDTO(d.getMontantTtc(), d.getDatePiece(), d.getTypeDocument().name()))
                .toList();
    }
}
