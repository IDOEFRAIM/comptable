package com.example.comptable.fiscalite.paiement.service;

import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.fiscalite.domain.DeclarationFiscale;
import com.example.comptable.fiscalite.domain.StatutDeclaration;
import com.example.comptable.fiscalite.domain.TypeImpot;
import com.example.comptable.fiscalite.paiement.domain.PaiementFiscal;
import com.example.comptable.fiscalite.paiement.domain.PaiementFournisseur;
import com.example.comptable.fiscalite.paiement.domain.StatutPaiement;
import com.example.comptable.fiscalite.paiement.provider.TaxPaymentInitiationResult;
import com.example.comptable.fiscalite.paiement.provider.TaxPaymentProvider;
import com.example.comptable.fiscalite.paiement.repository.PaiementFiscalRepository;
import com.example.comptable.fiscalite.paiement.web.dto.InitierPaiementRequest;
import com.example.comptable.fiscalite.paiement.web.dto.PaiementFiscalDto;
import com.example.comptable.fiscalite.repository.DeclarationFiscaleRepository;
import com.example.comptable.identite.domain.Role;
import com.example.comptable.identite.domain.Utilisateur;
import com.example.comptable.security.UtilisateurPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaiementFiscalServiceTest {

    @Mock
    private EntrepriseAccessService entrepriseAccessService;
    @Mock
    private DeclarationFiscaleRepository declarationFiscaleRepository;
    @Mock
    private PaiementFiscalRepository paiementFiscalRepository;
    @Mock
    private TaxPaymentProvider esyntaxeProvider;

    private PaiementFiscalService paiementFiscalService;

    private UUID entrepriseId;
    private Entreprise entreprise;
    private UtilisateurPrincipal principal;

    @BeforeEach
    void setUp() {
        paiementFiscalService = new PaiementFiscalService(
                entrepriseAccessService, declarationFiscaleRepository, paiementFiscalRepository, List.of(esyntaxeProvider));

        entrepriseId = UUID.randomUUID();
        entreprise = new Entreprise();
        entreprise.setId(entrepriseId);
        entreprise.setNumeroContribuable("00123456A");
        entreprise.setDevise("XOF");

        Role role = new Role();
        role.setCode("EXPERT_COMPTABLE");
        Utilisateur utilisateur = new Utilisateur();
        utilisateur.setId(UUID.randomUUID());
        utilisateur.setEmail("test@cabinet.bf");
        utilisateur.setMotDePasse("hash");
        utilisateur.setNom("Nom");
        utilisateur.setPrenom("Prenom");
        utilisateur.setRoles(Set.of(role));
        principal = new UtilisateurPrincipal(utilisateur);
    }

    private DeclarationFiscale declaration(StatutDeclaration statut, BigDecimal montant) {
        DeclarationFiscale declaration = new DeclarationFiscale();
        declaration.setId(UUID.randomUUID());
        declaration.setEntreprise(entreprise);
        declaration.setTypeImpot(TypeImpot.TVA);
        declaration.setPeriode("2026-03");
        declaration.setDateEcheance(LocalDate.of(2026, 4, 15));
        declaration.setStatut(statut);
        declaration.setMontant(montant);
        return declaration;
    }

    @Test
    void refuse_dinitier_un_paiement_pour_une_declaration_deja_payee() {
        DeclarationFiscale declaration = declaration(StatutDeclaration.PAYEE, new BigDecimal("10800"));
        when(entrepriseAccessService.verifierAcces(entrepriseId, principal)).thenReturn(entreprise);
        when(declarationFiscaleRepository.findById(declaration.getId())).thenReturn(Optional.of(declaration));

        InitierPaiementRequest request = new InitierPaiementRequest(PaiementFournisseur.E_SYNTAXE, null);

        assertThatThrownBy(() -> paiementFiscalService.initierPaiement(entrepriseId, declaration.getId(), request, principal))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("deja payee");
    }

    @Test
    void refuse_dinitier_un_paiement_sans_montant_renseigne() {
        DeclarationFiscale declaration = declaration(StatutDeclaration.A_FAIRE, null);
        when(entrepriseAccessService.verifierAcces(entrepriseId, principal)).thenReturn(entreprise);
        when(declarationFiscaleRepository.findById(declaration.getId())).thenReturn(Optional.of(declaration));

        InitierPaiementRequest request = new InitierPaiementRequest(PaiementFournisseur.E_SYNTAXE, null);

        assertThatThrownBy(() -> paiementFiscalService.initierPaiement(entrepriseId, declaration.getId(), request, principal))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("montant");
    }

    @Test
    void initie_un_paiement_et_laisse_la_declaration_non_payee_tant_que_non_confirmee() {
        DeclarationFiscale declaration = declaration(StatutDeclaration.A_FAIRE, new BigDecimal("10800"));
        when(entrepriseAccessService.verifierAcces(entrepriseId, principal)).thenReturn(entreprise);
        when(declarationFiscaleRepository.findById(declaration.getId())).thenReturn(Optional.of(declaration));
        when(esyntaxeProvider.fournisseur()).thenReturn(PaiementFournisseur.E_SYNTAXE);
        when(esyntaxeProvider.initierPaiement(any()))
                .thenReturn(TaxPaymentInitiationResult.enAttente("SIM-ESYNTAXE-1", "en attente"));
        when(paiementFiscalRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        InitierPaiementRequest request = new InitierPaiementRequest(PaiementFournisseur.E_SYNTAXE, null);
        PaiementFiscalDto resultat = paiementFiscalService.initierPaiement(entrepriseId, declaration.getId(), request, principal);

        assertThat(resultat.statut()).isEqualTo(StatutPaiement.EN_ATTENTE_CONFIRMATION.name());
        assertThat(resultat.referenceExterne()).isEqualTo("SIM-ESYNTAXE-1");
        assertThat(declaration.getStatut()).isEqualTo(StatutDeclaration.A_FAIRE);
    }

    @Test
    void confirmer_un_paiement_reussi_marque_la_declaration_payee() {
        DeclarationFiscale declaration = declaration(StatutDeclaration.A_FAIRE, new BigDecimal("10800"));
        PaiementFiscal paiement = new PaiementFiscal();
        paiement.setId(UUID.randomUUID());
        paiement.setDeclarationFiscale(declaration);
        paiement.setFournisseur(PaiementFournisseur.E_SYNTAXE);
        paiement.setMontant(declaration.getMontant());
        paiement.setStatut(StatutPaiement.EN_ATTENTE_CONFIRMATION);
        paiement.setReferenceExterne("SIM-ESYNTAXE-1");

        when(entrepriseAccessService.verifierAcces(entrepriseId, principal)).thenReturn(entreprise);
        when(declarationFiscaleRepository.findById(declaration.getId())).thenReturn(Optional.of(declaration));
        when(paiementFiscalRepository.findById(paiement.getId())).thenReturn(Optional.of(paiement));
        when(paiementFiscalRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(declarationFiscaleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PaiementFiscalDto resultat = paiementFiscalService.confirmerParId(
                entrepriseId, declaration.getId(), paiement.getId(), true, principal);

        assertThat(resultat.statut()).isEqualTo(StatutPaiement.REUSSI.name());
        assertThat(declaration.getStatut()).isEqualTo(StatutDeclaration.PAYEE);
        assertThat(declaration.getDatePaiement()).isEqualTo(LocalDate.now());
    }

    @Test
    void refuse_de_confirmer_un_paiement_sans_reference_externe() {
        DeclarationFiscale declaration = declaration(StatutDeclaration.A_FAIRE, new BigDecimal("10800"));
        PaiementFiscal paiement = new PaiementFiscal();
        paiement.setId(UUID.randomUUID());
        paiement.setDeclarationFiscale(declaration);
        paiement.setFournisseur(PaiementFournisseur.FASO_ARZEKA);
        paiement.setMontant(declaration.getMontant());
        paiement.setStatut(StatutPaiement.ECHEC);

        when(entrepriseAccessService.verifierAcces(entrepriseId, principal)).thenReturn(entreprise);
        when(declarationFiscaleRepository.findById(declaration.getId())).thenReturn(Optional.of(declaration));
        when(paiementFiscalRepository.findById(paiement.getId())).thenReturn(Optional.of(paiement));

        assertThatThrownBy(() -> paiementFiscalService.confirmerParId(
                entrepriseId, declaration.getId(), paiement.getId(), true, principal))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("reference externe");
    }
}
