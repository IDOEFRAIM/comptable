package com.example.comptable.fiscalite.service;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.repository.CompteComptableRepository;
import com.example.comptable.comptabilite.repository.LigneEcritureRepository;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.fiscalite.domain.DeclarationFiscale;
import com.example.comptable.fiscalite.domain.StatutDeclaration;
import com.example.comptable.fiscalite.domain.TypeImpot;
import com.example.comptable.fiscalite.repository.DeclarationFiscaleRepository;
import com.example.comptable.fiscalite.web.dto.DeclarationFiscaleDto;
import com.example.comptable.identite.domain.Role;
import com.example.comptable.identite.domain.Utilisateur;
import com.example.comptable.security.UtilisateurPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeclarationFiscaleServiceTest {

    @Mock
    private EntrepriseAccessService entrepriseAccessService;
    @Mock
    private DeclarationFiscaleRepository declarationFiscaleRepository;
    @Mock
    private CompteComptableRepository compteComptableRepository;
    @Mock
    private LigneEcritureRepository ligneEcritureRepository;

    @InjectMocks
    private DeclarationFiscaleService declarationFiscaleService;

    private UUID entrepriseId;
    private Entreprise entreprise;
    private UtilisateurPrincipal principal;
    private CompteComptable compteCollectee;
    private CompteComptable compteDeductible;

    @BeforeEach
    void setUp() {
        entrepriseId = UUID.randomUUID();
        entreprise = new Entreprise();
        entreprise.setId(entrepriseId);

        Role role = new Role();
        role.setCode("EXPERT_COMPTABLE");
        Utilisateur utilisateur = new Utilisateur();
        utilisateur.setId(UUID.randomUUID());
        utilisateur.setEmail("test@cabinet.ci");
        utilisateur.setMotDePasse("hash");
        utilisateur.setNom("Nom");
        utilisateur.setPrenom("Prenom");
        utilisateur.setRoles(Set.of(role));
        principal = new UtilisateurPrincipal(utilisateur);

        compteCollectee = new CompteComptable();
        compteCollectee.setId(UUID.randomUUID());
        compteCollectee.setEntreprise(entreprise);
        compteCollectee.setNumero("443");

        compteDeductible = new CompteComptable();
        compteDeductible.setId(UUID.randomUUID());
        compteDeductible.setEntreprise(entreprise);
        compteDeductible.setNumero("4456");
    }

    private DeclarationFiscale declarationTva(String periode, StatutDeclaration statut) {
        DeclarationFiscale declaration = new DeclarationFiscale();
        declaration.setId(UUID.randomUUID());
        declaration.setEntreprise(entreprise);
        declaration.setTypeImpot(TypeImpot.TVA);
        declaration.setPeriode(periode);
        declaration.setDateEcheance(LocalDate.of(2026, 4, 15));
        declaration.setStatut(statut);
        return declaration;
    }

    @Test
    void recalcule_le_montant_tva_depuis_les_ecritures_validees() {
        DeclarationFiscale declaration = declarationTva("2026-03", StatutDeclaration.A_FAIRE);
        when(entrepriseAccessService.verifierAcces(entrepriseId, principal)).thenReturn(entreprise);
        when(declarationFiscaleRepository.findById(declaration.getId())).thenReturn(Optional.of(declaration));
        when(compteComptableRepository.findByEntrepriseIdAndNumero(entrepriseId, "443"))
                .thenReturn(Optional.of(compteCollectee));
        when(compteComptableRepository.findByEntrepriseIdAndNumero(entrepriseId, "4456"))
                .thenReturn(Optional.of(compteDeductible));
        when(ligneEcritureRepository.mouvementNetPeriode(compteCollectee.getId(), LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31)))
                .thenReturn(new BigDecimal("18000"));
        when(ligneEcritureRepository.mouvementNetPeriode(compteDeductible.getId(), LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31)))
                .thenReturn(new BigDecimal("-7200"));
        when(declarationFiscaleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DeclarationFiscaleDto resultat = declarationFiscaleService.recalculerMontantTva(entrepriseId, declaration.getId(), principal);

        assertThat(resultat.montant()).isEqualByComparingTo("10800");
    }

    @Test
    void refuse_le_recalcul_pour_un_type_dimpot_different_de_tva() {
        DeclarationFiscale declaration = declarationTva("2026-03", StatutDeclaration.A_FAIRE);
        declaration.setTypeImpot(TypeImpot.IS);
        when(entrepriseAccessService.verifierAcces(entrepriseId, principal)).thenReturn(entreprise);
        when(declarationFiscaleRepository.findById(declaration.getId())).thenReturn(Optional.of(declaration));

        assertThatThrownBy(() -> declarationFiscaleService.recalculerMontantTva(entrepriseId, declaration.getId(), principal))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("TVA");
    }

    @Test
    void refuse_le_recalcul_dune_declaration_deja_payee() {
        DeclarationFiscale declaration = declarationTva("2026-03", StatutDeclaration.PAYEE);
        when(entrepriseAccessService.verifierAcces(entrepriseId, principal)).thenReturn(entreprise);
        when(declarationFiscaleRepository.findById(declaration.getId())).thenReturn(Optional.of(declaration));

        assertThatThrownBy(() -> declarationFiscaleService.recalculerMontantTva(entrepriseId, declaration.getId(), principal))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("deja payee");
    }
}
