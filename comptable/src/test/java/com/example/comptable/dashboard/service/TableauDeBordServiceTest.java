package com.example.comptable.dashboard.service;

import com.example.comptable.comptabilite.domain.StatutEcriture;
import com.example.comptable.comptabilite.repository.EcritureComptableRepository;
import com.example.comptable.comptabilite.repository.LigneEcritureRepository;
import com.example.comptable.dashboard.web.dto.TableauDeBordDto;
import com.example.comptable.document.domain.StatutDocument;
import com.example.comptable.document.repository.DocumentRepository;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.fiscalite.domain.DeclarationFiscale;
import com.example.comptable.fiscalite.domain.StatutDeclaration;
import com.example.comptable.fiscalite.domain.TypeImpot;
import com.example.comptable.fiscalite.repository.DeclarationFiscaleRepository;
import com.example.comptable.identite.domain.Role;
import com.example.comptable.identite.domain.Utilisateur;
import com.example.comptable.security.UtilisateurPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TableauDeBordServiceTest {

    @Mock
    private EntrepriseAccessService entrepriseAccessService;
    @Mock
    private DocumentRepository documentRepository;
    @Mock
    private EcritureComptableRepository ecritureComptableRepository;
    @Mock
    private LigneEcritureRepository ligneEcritureRepository;
    @Mock
    private DeclarationFiscaleRepository declarationFiscaleRepository;

    @InjectMocks
    private TableauDeBordService tableauDeBordService;

    private UUID entrepriseId;
    private UtilisateurPrincipal principal;

    @BeforeEach
    void setUp() {
        entrepriseId = UUID.randomUUID();

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

        when(entrepriseAccessService.verifierAcces(entrepriseId, principal)).thenReturn(new Entreprise());
    }

    private DeclarationFiscale declaration(LocalDate echeance, StatutDeclaration statut) {
        DeclarationFiscale d = new DeclarationFiscale();
        d.setTypeImpot(TypeImpot.TVA);
        d.setPeriode("2026-01");
        d.setDateEcheance(echeance);
        d.setStatut(statut);
        return d;
    }

    @Test
    void agrege_les_compteurs_et_les_echeances() {
        when(documentRepository.countByEntrepriseIdAndStatutAndTypeDocumentInAndEcritureIdIsNull(
                eq(entrepriseId), eq(StatutDocument.TRAITE), any())).thenReturn(3L);
        when(documentRepository.countByEntrepriseIdAndStatut(entrepriseId, StatutDocument.ERREUR)).thenReturn(2L);
        when(ecritureComptableRepository.countByEntrepriseIdAndStatut(entrepriseId, StatutEcriture.BROUILLON))
                .thenReturn(4L);
        when(ligneEcritureRepository.soldeTresorerie(eq(entrepriseId), any())).thenReturn(new BigDecimal("500000"));

        DeclarationFiscale prochaine = declaration(LocalDate.now().plusDays(10), StatutDeclaration.A_FAIRE);
        DeclarationFiscale retard = declaration(LocalDate.now().minusDays(5), StatutDeclaration.A_FAIRE);

        when(declarationFiscaleRepository.findByEntrepriseIdAndStatutNotAndDateEcheanceBetweenOrderByDateEcheanceAsc(
                eq(entrepriseId), eq(StatutDeclaration.PAYEE), any(), any())).thenReturn(List.of(prochaine));
        when(declarationFiscaleRepository.findByEntrepriseIdAndStatutNotAndDateEcheanceBeforeOrderByDateEcheanceAsc(
                eq(entrepriseId), eq(StatutDeclaration.PAYEE), any())).thenReturn(List.of(retard));

        TableauDeBordDto resultat = tableauDeBordService.generer(entrepriseId, principal);

        assertThat(resultat.documentsATraiter()).isEqualTo(3L);
        assertThat(resultat.documentsEnErreur()).isEqualTo(2L);
        assertThat(resultat.ecrituresBrouillon()).isEqualTo(4L);
        assertThat(resultat.soldeTresorerie().debit()).isEqualByComparingTo("500000");
        assertThat(resultat.prochainesEcheances()).hasSize(1);
        assertThat(resultat.echeancesEnRetard()).hasSize(1);
        assertThat(resultat.echeancesEnRetard().get(0).enRetard()).isTrue();
        assertThat(resultat.prochainesEcheances().get(0).enRetard()).isFalse();
    }

    @Test
    void solde_tresorerie_negatif_apparait_au_credit() {
        when(documentRepository.countByEntrepriseIdAndStatutAndTypeDocumentInAndEcritureIdIsNull(
                eq(entrepriseId), eq(StatutDocument.TRAITE), any())).thenReturn(0L);
        when(documentRepository.countByEntrepriseIdAndStatut(entrepriseId, StatutDocument.ERREUR)).thenReturn(0L);
        when(ecritureComptableRepository.countByEntrepriseIdAndStatut(entrepriseId, StatutEcriture.BROUILLON))
                .thenReturn(0L);
        when(ligneEcritureRepository.soldeTresorerie(eq(entrepriseId), any())).thenReturn(new BigDecimal("-1000"));
        when(declarationFiscaleRepository.findByEntrepriseIdAndStatutNotAndDateEcheanceBetweenOrderByDateEcheanceAsc(
                any(), any(), any(), any())).thenReturn(List.of());
        when(declarationFiscaleRepository.findByEntrepriseIdAndStatutNotAndDateEcheanceBeforeOrderByDateEcheanceAsc(
                any(), any(), any())).thenReturn(List.of());

        TableauDeBordDto resultat = tableauDeBordService.generer(entrepriseId, principal);

        assertThat(resultat.soldeTresorerie().debit()).isEqualByComparingTo("0");
        assertThat(resultat.soldeTresorerie().credit()).isEqualByComparingTo("1000");
    }
}
