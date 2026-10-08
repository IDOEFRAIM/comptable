package com.example.comptable.fiscalite.paiement.service;

import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.fiscalite.domain.DeclarationFiscale;
import com.example.comptable.fiscalite.domain.StatutDeclaration;
import com.example.comptable.fiscalite.paiement.domain.PaiementFiscal;
import com.example.comptable.fiscalite.paiement.domain.PaiementFournisseur;
import com.example.comptable.fiscalite.paiement.domain.StatutPaiement;
import com.example.comptable.fiscalite.paiement.provider.TaxPaymentInitiationResult;
import com.example.comptable.fiscalite.paiement.provider.TaxPaymentProvider;
import com.example.comptable.fiscalite.paiement.provider.TaxPaymentRequest;
import com.example.comptable.fiscalite.paiement.repository.PaiementFiscalRepository;
import com.example.comptable.fiscalite.paiement.web.dto.InitierPaiementRequest;
import com.example.comptable.fiscalite.paiement.web.dto.PaiementFiscalDto;
import com.example.comptable.fiscalite.repository.DeclarationFiscaleRepository;
import com.example.comptable.security.UtilisateurPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Orchestration du telepaiement des declarations fiscales aupres des fournisseurs externes
 * (e-Syntaxe, Faso Arzeka - Burkina Faso). Ne connait aucun detail d'API fournisseur : delegue a
 * l'implementation {@link TaxPaymentProvider} correspondante, trace chaque tentative dans
 * {@link PaiementFiscal}, et ne marque la declaration PAYEE qu'une fois le paiement confirme
 * (jamais a la simple initiation, qui n'est qu'une demande envoyee au fournisseur).
 */
@Service
@RequiredArgsConstructor
public class PaiementFiscalService {

    private final EntrepriseAccessService entrepriseAccessService;
    private final DeclarationFiscaleRepository declarationFiscaleRepository;
    private final PaiementFiscalRepository paiementFiscalRepository;
    private final List<TaxPaymentProvider> providers;

    private Map<PaiementFournisseur, TaxPaymentProvider> providersParFournisseur() {
        return providers.stream().collect(Collectors.toMap(TaxPaymentProvider::fournisseur, Function.identity()));
    }

    @Transactional(readOnly = true)
    public List<PaiementFiscalDto> lister(UUID entrepriseId, UUID declarationId, UtilisateurPrincipal principal) {
        DeclarationFiscale declaration = charger(entrepriseId, declarationId, principal);
        return paiementFiscalRepository.findByDeclarationFiscaleIdOrderByDateInitiationDesc(declaration.getId()).stream()
                .map(PaiementFiscalDto::from)
                .toList();
    }

    @Transactional
    public PaiementFiscalDto initierPaiement(UUID entrepriseId, UUID declarationId, InitierPaiementRequest request,
                                              UtilisateurPrincipal principal) {
        DeclarationFiscale declaration = charger(entrepriseId, declarationId, principal);
        Entreprise entreprise = declaration.getEntreprise();

        if (declaration.getStatut() == StatutDeclaration.PAYEE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cette declaration est deja payee");
        }
        if (declaration.getMontant() == null || declaration.getMontant().signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Le montant de la declaration doit etre renseigne avant d'initier un paiement");
        }

        TaxPaymentProvider provider = providersParFournisseur().get(request.fournisseur());
        if (provider == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Fournisseur de paiement inconnu");
        }

        PaiementFiscal paiement = new PaiementFiscal();
        paiement.setDeclarationFiscale(declaration);
        paiement.setFournisseur(request.fournisseur());
        paiement.setMontant(declaration.getMontant());
        paiement.setNumeroTelephonePayeur(request.numeroTelephonePayeur());
        paiement = paiementFiscalRepository.save(paiement);

        TaxPaymentRequest requeteFournisseur = new TaxPaymentRequest(
                paiement.getId(),
                entreprise.getNumeroContribuable(),
                declaration.getTypeImpot(),
                declaration.getPeriode(),
                declaration.getMontant(),
                entreprise.getDevise(),
                request.numeroTelephonePayeur());

        TaxPaymentInitiationResult resultat = provider.initierPaiement(requeteFournisseur);
        paiement.setStatut(resultat.statut());
        paiement.setReferenceExterne(resultat.referenceExterne());
        paiement.setMessageStatut(resultat.message());
        return PaiementFiscalDto.from(paiementFiscalRepository.save(paiement));
    }

    /**
     * A appeler par le webhook du fournisseur une fois l'integration reelle branchee (voir
     * {@code PaiementWebhookController}). Idempotent : reconfirmer un paiement deja REUSSI ne fait
     * rien de plus que retourner son etat actuel.
     */
    @Transactional
    public PaiementFiscalDto confirmer(PaiementFournisseur fournisseur, String referenceExterne, boolean reussite,
                                        String message) {
        PaiementFiscal paiement = paiementFiscalRepository.findByFournisseurAndReferenceExterne(fournisseur, referenceExterne)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Aucun paiement " + fournisseur + " avec la reference " + referenceExterne));
        return appliquerConfirmation(paiement, reussite, message);
    }

    /**
     * Confirmation manuelle "a la main", reservee au mode simulation (voir {@code esyntaxe.mode} /
     * {@code fasoarzeka.mode}) en l'absence de webhook reel des fournisseurs.
     */
    @Transactional
    public PaiementFiscalDto confirmerParId(UUID entrepriseId, UUID declarationId, UUID paiementId, boolean reussite,
                                             UtilisateurPrincipal principal) {
        DeclarationFiscale declaration = charger(entrepriseId, declarationId, principal);
        PaiementFiscal paiement = paiementFiscalRepository.findById(paiementId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Paiement introuvable"));
        if (!paiement.getDeclarationFiscale().getId().equals(declaration.getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Paiement introuvable");
        }
        if (paiement.getReferenceExterne() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Ce paiement n'a pas de reference externe (echec immediat a l'initiation), rien a confirmer");
        }
        return appliquerConfirmation(paiement, reussite,
                reussite ? "Confirmation manuelle (mode simulation)" : "Echec simule manuellement");
    }

    private PaiementFiscalDto appliquerConfirmation(PaiementFiscal paiement, boolean reussite, String message) {
        if (paiement.getStatut() == StatutPaiement.REUSSI) {
            return PaiementFiscalDto.from(paiement);
        }

        paiement.setStatut(reussite ? StatutPaiement.REUSSI : StatutPaiement.ECHEC);
        paiement.setMessageStatut(message);
        paiement.setDateConfirmation(OffsetDateTime.now());
        paiementFiscalRepository.save(paiement);

        if (reussite) {
            DeclarationFiscale declaration = paiement.getDeclarationFiscale();
            declaration.setStatut(StatutDeclaration.PAYEE);
            declaration.setDatePaiement(OffsetDateTime.now().toLocalDate());
            if (declaration.getDateDeclaration() == null) {
                declaration.setDateDeclaration(declaration.getDatePaiement());
            }
            declarationFiscaleRepository.save(declaration);
        }

        return PaiementFiscalDto.from(paiement);
    }

    private DeclarationFiscale charger(UUID entrepriseId, UUID declarationId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        DeclarationFiscale declaration = declarationFiscaleRepository.findById(declarationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Declaration introuvable"));
        if (!declaration.getEntreprise().getId().equals(entrepriseId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Declaration introuvable");
        }
        return declaration;
    }
}
