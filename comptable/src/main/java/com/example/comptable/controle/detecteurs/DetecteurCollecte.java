package com.example.comptable.controle.detecteurs;

import com.example.comptable.collecte.service.CollecteConfig;
import com.example.comptable.collecte.service.ContactResolver;
import com.example.comptable.collecte.service.DemandeMatcher;
import com.example.comptable.controle.ControlCategory;
import com.example.comptable.controle.ControlCodes;
import com.example.comptable.controle.ControlDetector;
import com.example.comptable.controle.ControlResult;
import com.example.comptable.controle.ControlSeverity;
import com.example.comptable.demande.domain.DemandePiece;
import com.example.comptable.demande.domain.StatutDemandePiece;
import com.example.comptable.demande.repository.DemandePieceRepository;
import com.example.comptable.document.domain.Document;
import com.example.comptable.document.domain.StatutDemandeDocument;
import com.example.comptable.document.repository.DocumentRepository;
import com.example.comptable.entreprise.domain.Entreprise;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Constats de la COLLECTE : ce qui demande reellement le comptable, rien de plus. Une relance reussie n'est PAS une anomalie
 * (aucun constat) ; un echec technique reessaye n'en est pas une non plus tant que le retry est en cours.
 * <ul>
 *   <li>{@code DOCUMENT_REQUEST_NO_CONTACT} : demandes qui ne peuvent pas partir (aucun contact / numero invalide) - UN constat par
 *       entreprise, avec le nombre de demandes par cause ;</li>
 *   <li>{@code DOCUMENT_REQUEST_SEND_FAILED} : demandes non envoyees apres les nouvelles tentatives - UN constat par entreprise ;</li>
 *   <li>{@code DOCUMENT_REQUEST_NO_RESPONSE} : le client ne repond pas apres toutes les relances de la politique - un par demande ;</li>
 *   <li>{@code DOCUMENT_MATCH_TO_CONFIRM} / {@code DOCUMENT_RECEIVED_AMBIGUOUS} : une piece recue ressemble a une ou plusieurs demandes.</li>
 * </ul>
 */
@Component
public class DetecteurCollecte implements ControlDetector {

    static final int MAX_IDS_LIES = 50;

    private final DemandePieceRepository demandePieceRepository;
    private final DocumentRepository documentRepository;
    private final DemandeMatcher demandeMatcher;
    private final CollecteConfig config;

    public DetecteurCollecte(DemandePieceRepository demandePieceRepository, DocumentRepository documentRepository,
                             DemandeMatcher demandeMatcher, CollecteConfig config) {
        this.demandePieceRepository = demandePieceRepository;
        this.documentRepository = documentRepository;
        this.demandeMatcher = demandeMatcher;
        this.config = config;
    }

    @Override
    public String nom() {
        return "collecte";
    }

    @Override
    public Set<String> codes() {
        return Set.of(ControlCodes.DOCUMENT_REQUEST_NO_CONTACT, ControlCodes.DOCUMENT_REQUEST_SEND_FAILED,
                ControlCodes.DOCUMENT_REQUEST_NO_RESPONSE, ControlCodes.DOCUMENT_MATCH_TO_CONFIRM, ControlCodes.DOCUMENT_RECEIVED_AMBIGUOUS);
    }

    @Override
    public List<ControlResult> detecter(Entreprise entreprise) {
        List<ControlResult> resultats = new ArrayList<>();

        List<DemandePiece> bloquees = demandePieceRepository.findOuvertes(entreprise.getId(), EnumSet.of(StatutDemandePiece.ECHEC_ENVOI));
        List<DemandePiece> sansContact = bloquees.stream().filter(d -> isIn(d.getMotifBlocage(), ContactResolver.NO_CONTACT, ContactResolver.INVALID_CONTACT)
                || d.getMotifBlocage() == null).toList();
        List<DemandePiece> echecs = bloquees.stream().filter(d -> !sansContact.contains(d)).toList();
        if (!sansContact.isEmpty()) {
            Map<String, Object> parCause = new LinkedHashMap<>();
            long aucun = sansContact.stream().filter(d -> !ContactResolver.INVALID_CONTACT.equals(d.getMotifBlocage())).count();
            parCause.put("aucunContact", aucun);
            parCause.put("numeroInvalide", sansContact.size() - aucun);
            parCause.put("nbDemandes", sansContact.size());
            resultats.add(ControlResult.ouvert(ControlCodes.DOCUMENT_REQUEST_NO_CONTACT, ControlCategory.DOCUMENT, ControlSeverity.WARNING,
                    entreprise.getId(), "ENTREPRISE", entreprise.getId().toString(),
                    sansContact.size() + " demande(s) de piece impossibles a envoyer",
                    aucun + " demande(s) sans interlocuteur joignable (aucun client rattache ou aucun telephone) et "
                            + (sansContact.size() - aucun) + " avec un numero invalide : le client ne peut pas etre contacte.",
                    sansContact.stream().limit(MAX_IDS_LIES).map(d -> d.getId().toString()).toList(), parCause,
                    "Inviter le client avec un numero WhatsApp valide (indicatif pays inclus) puis renvoyer les demandes."));
        }
        if (!echecs.isEmpty()) {
            resultats.add(ControlResult.ouvert(ControlCodes.DOCUMENT_REQUEST_SEND_FAILED, ControlCategory.DOCUMENT, ControlSeverity.WARNING,
                    entreprise.getId(), "ENTREPRISE", entreprise.getId().toString(),
                    echecs.size() + " demande(s) de piece non envoyee(s)",
                    echecs.size() + " demande(s) n'ont pas pu etre envoyees malgre plusieurs tentatives (fournisseur de messagerie indisponible ou refus).",
                    echecs.stream().limit(MAX_IDS_LIES).map(d -> d.getId().toString()).toList(), Map.of("nbDemandes", echecs.size()),
                    "Verifier le canal d'envoi puis renvoyer les demandes depuis la collecte."));
        }

        // Politique de relance epuisee : le client ne repond pas, la main passe a l'humain.
        for (DemandePiece d : demandePieceRepository.findOuvertes(entreprise.getId(), EnumSet.of(StatutDemandePiece.EN_ATTENTE))) {
            if (d.getProchaineRelanceAt() == null && d.getSentAt() != null && d.getNbRelances() >= config.maxRelances()) {
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("nbRelances", d.getNbRelances());
                if (d.getMontant() != null) {
                    metadata.put("montant", d.getMontant());
                }
                resultats.add(ControlResult.ouvert(ControlCodes.DOCUMENT_REQUEST_NO_RESPONSE, ControlCategory.DOCUMENT, ControlSeverity.WARNING,
                        entreprise.getId(), "DEMANDE_PIECE", d.getId().toString(),
                        "Client sans reponse : " + (d.getDescriptionOperation() != null ? d.getDescriptionOperation() : d.getLibelle()),
                        "La demande a ete envoyee le " + d.getSentAt().toLocalDate() + " et relancee " + d.getNbRelances()
                                + " fois sans reponse : la politique de relance est epuisee.",
                        List.of(), metadata, "Contacter le client directement, ou marquer l'operation comme justifiee si la piece n'est pas exigible."));
            }
        }

        for (Document d : documentRepository.rechercherParStatutDemande(entreprise.getId(), StatutDemandeDocument.A_CONFIRMER)) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("fichier", nom(d));
            if (d.getMontantTtc() != null) {
                metadata.put("montantTtc", d.getMontantTtc());
            }
            resultats.add(ControlResult.ouvert(ControlCodes.DOCUMENT_MATCH_TO_CONFIRM, ControlCategory.DOCUMENT, ControlSeverity.INFO,
                    entreprise.getId(), "DOCUMENT", d.getId().toString(), "Piece recue a confirmer : " + nom(d),
                    "Cette piece ressemble a une piece demandee (meme montant, date ou tiers proches) mais sans certitude : elle ne resout rien tant que le comptable ne confirme pas.",
                    d.getDemandeId() != null ? List.of(d.getDemandeId().toString()) : List.of(), metadata,
                    "Confirmer que cette piece repond a la demande, ou la rattacher a une autre."));
        }
        for (Document d : documentRepository.rechercherParStatutDemande(entreprise.getId(), StatutDemandeDocument.AMBIGU)) {
            List<String> candidates = demandeMatcher.proposer(d, null).candidates().stream().map(c -> c.getId().toString()).toList();
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("fichier", nom(d));
            metadata.put("nbCandidates", candidates.size());
            resultats.add(ControlResult.ouvert(ControlCodes.DOCUMENT_RECEIVED_AMBIGUOUS, ControlCategory.DOCUMENT, ControlSeverity.WARNING,
                    entreprise.getId(), "DOCUMENT", d.getId().toString(), "Piece recue ambigue : " + nom(d),
                    "Cette piece pourrait repondre a " + Math.max(candidates.size(), 2) + " demandes de piece : le systeme ne choisit jamais la premiere, aucune demande n'est resolue.",
                    candidates, metadata, "Choisir la demande a laquelle cette piece correspond."));
        }
        return resultats;
    }

    private static boolean isIn(String valeur, String... attendus) {
        for (String a : attendus) {
            if (a.equals(valeur)) {
                return true;
            }
        }
        return false;
    }

    private static String nom(Document d) {
        return d.getNomFichierOriginal() != null ? d.getNomFichierOriginal() : d.getId().toString();
    }
}
