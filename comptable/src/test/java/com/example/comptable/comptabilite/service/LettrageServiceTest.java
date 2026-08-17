package com.example.comptable.comptabilite.service;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.EcritureComptable;
import com.example.comptable.comptabilite.domain.LigneEcriture;
import com.example.comptable.comptabilite.domain.StatutEcriture;
import com.example.comptable.comptabilite.repository.CompteComptableRepository;
import com.example.comptable.comptabilite.repository.LigneEcritureRepository;
import com.example.comptable.comptabilite.web.dto.LettrageResultDto;
import com.example.comptable.comptabilite.web.dto.LettrerLignesRequest;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
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
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LettrageServiceTest {

    @Mock
    private EntrepriseAccessService entrepriseAccessService;
    @Mock
    private CompteComptableRepository compteComptableRepository;
    @Mock
    private LigneEcritureRepository ligneEcritureRepository;

    @InjectMocks
    private LettrageService lettrageService;

    private UUID entrepriseId;
    private Entreprise entreprise;
    private UtilisateurPrincipal principal;
    private CompteComptable compteClient;

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

        compteClient = new CompteComptable();
        compteClient.setId(UUID.randomUUID());
        compteClient.setEntreprise(entreprise);
        compteClient.setNumero("411001");
        compteClient.setLettrable(true);

        when(entrepriseAccessService.verifierAcces(entrepriseId, principal)).thenReturn(entreprise);
    }

    private LigneEcriture ligne(BigDecimal debit, BigDecimal credit, StatutEcriture statut, String lettrageExistant) {
        EcritureComptable ecriture = new EcritureComptable();
        ecriture.setId(UUID.randomUUID());
        ecriture.setEntreprise(entreprise);
        ecriture.setStatut(statut);
        ecriture.setDateEcriture(java.time.LocalDate.of(2026, 3, 1));

        LigneEcriture ligne = new LigneEcriture();
        ligne.setId(UUID.randomUUID());
        ligne.setEcriture(ecriture);
        ligne.setCompte(compteClient);
        ligne.setDebit(debit);
        ligne.setCredit(credit);
        ligne.setLettrage(lettrageExistant);
        return ligne;
    }

    @Test
    void lettre_deux_lignes_equilibrees() {
        LigneEcriture facture = ligne(new BigDecimal("50000"), BigDecimal.ZERO, StatutEcriture.VALIDEE, null);
        LigneEcriture reglement = ligne(BigDecimal.ZERO, new BigDecimal("50000"), StatutEcriture.VALIDEE, null);
        List<UUID> ids = List.of(facture.getId(), reglement.getId());

        when(compteComptableRepository.findById(compteClient.getId())).thenReturn(Optional.of(compteClient));
        when(ligneEcritureRepository.findAllById(ids)).thenReturn(List.of(facture, reglement));
        when(ligneEcritureRepository.countDistinctLettrageByCompteId(compteClient.getId())).thenReturn(0L);
        when(ligneEcritureRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        LettrageResultDto resultat = lettrageService.lettrer(entrepriseId,
                new LettrerLignesRequest(compteClient.getId(), ids, false), principal);

        assertThat(resultat.code()).isEqualTo("A");
        assertThat(facture.getLettrage()).isEqualTo("A");
        assertThat(reglement.getLettrage()).isEqualTo("A");
    }

    @Test
    void refuse_un_groupe_desequilibre() {
        LigneEcriture facture = ligne(new BigDecimal("50000"), BigDecimal.ZERO, StatutEcriture.VALIDEE, null);
        LigneEcriture reglementPartiel = ligne(BigDecimal.ZERO, new BigDecimal("30000"), StatutEcriture.VALIDEE, null);
        List<UUID> ids = List.of(facture.getId(), reglementPartiel.getId());

        when(compteComptableRepository.findById(compteClient.getId())).thenReturn(Optional.of(compteClient));
        when(ligneEcritureRepository.findAllById(ids)).thenReturn(List.of(facture, reglementPartiel));

        assertThatThrownBy(() -> lettrageService.lettrer(entrepriseId,
                new LettrerLignesRequest(compteClient.getId(), ids, false), principal))
                .hasMessageContaining("equilibre");
    }

    @Test
    void refuse_une_ligne_deja_lettree() {
        LigneEcriture facture = ligne(new BigDecimal("50000"), BigDecimal.ZERO, StatutEcriture.VALIDEE, "A");
        LigneEcriture reglement = ligne(BigDecimal.ZERO, new BigDecimal("50000"), StatutEcriture.VALIDEE, null);
        List<UUID> ids = List.of(facture.getId(), reglement.getId());

        when(compteComptableRepository.findById(compteClient.getId())).thenReturn(Optional.of(compteClient));
        when(ligneEcritureRepository.findAllById(ids)).thenReturn(List.of(facture, reglement));

        assertThatThrownBy(() -> lettrageService.lettrer(entrepriseId,
                new LettrerLignesRequest(compteClient.getId(), ids, false), principal))
                .hasMessageContaining("deja lettree");
    }

    @Test
    void refuse_une_ligne_en_brouillon() {
        LigneEcriture facture = ligne(new BigDecimal("50000"), BigDecimal.ZERO, StatutEcriture.BROUILLON, null);
        LigneEcriture reglement = ligne(BigDecimal.ZERO, new BigDecimal("50000"), StatutEcriture.VALIDEE, null);
        List<UUID> ids = List.of(facture.getId(), reglement.getId());

        when(compteComptableRepository.findById(compteClient.getId())).thenReturn(Optional.of(compteClient));
        when(ligneEcritureRepository.findAllById(ids)).thenReturn(List.of(facture, reglement));

        assertThatThrownBy(() -> lettrageService.lettrer(entrepriseId,
                new LettrerLignesRequest(compteClient.getId(), ids, false), principal))
                .hasMessageContaining("validees");
    }

    @Test
    void genere_le_code_suivant_apres_25_lettrages_existants() {
        LigneEcriture facture = ligne(new BigDecimal("50000"), BigDecimal.ZERO, StatutEcriture.VALIDEE, null);
        LigneEcriture reglement = ligne(BigDecimal.ZERO, new BigDecimal("50000"), StatutEcriture.VALIDEE, null);
        List<UUID> ids = List.of(facture.getId(), reglement.getId());

        when(compteComptableRepository.findById(compteClient.getId())).thenReturn(Optional.of(compteClient));
        when(ligneEcritureRepository.findAllById(ids)).thenReturn(List.of(facture, reglement));
        when(ligneEcritureRepository.countDistinctLettrageByCompteId(compteClient.getId())).thenReturn(26L);
        when(ligneEcritureRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        LettrageResultDto resultat = lettrageService.lettrer(entrepriseId,
                new LettrerLignesRequest(compteClient.getId(), ids, false), principal);

        assertThat(resultat.code()).isEqualTo("AA");
    }

    @Test
    void refuse_une_ligne_dune_autre_entreprise() {
        LigneEcriture facture = ligne(new BigDecimal("50000"), BigDecimal.ZERO, StatutEcriture.VALIDEE, null);
        LigneEcriture reglement = ligne(BigDecimal.ZERO, new BigDecimal("50000"), StatutEcriture.VALIDEE, null);
        reglement.getEcriture().setEntreprise(new Entreprise());
        reglement.getEcriture().getEntreprise().setId(UUID.randomUUID());
        List<UUID> ids = List.of(facture.getId(), reglement.getId());

        when(compteComptableRepository.findById(compteClient.getId())).thenReturn(Optional.of(compteClient));
        when(ligneEcritureRepository.findAllById(ids)).thenReturn(List.of(facture, reglement));

        assertThatThrownBy(() -> lettrageService.lettrer(entrepriseId,
                new LettrerLignesRequest(compteClient.getId(), ids, false), principal))
                .hasMessageContaining("introuvable");
    }

    @Test
    void refuse_une_ligne_dun_autre_compte() {
        CompteComptable autreCompte = new CompteComptable();
        autreCompte.setId(UUID.randomUUID());
        autreCompte.setEntreprise(entreprise);
        autreCompte.setNumero("401001");
        autreCompte.setLettrable(true);

        LigneEcriture facture = ligne(new BigDecimal("50000"), BigDecimal.ZERO, StatutEcriture.VALIDEE, null);
        LigneEcriture reglement = ligne(BigDecimal.ZERO, new BigDecimal("50000"), StatutEcriture.VALIDEE, null);
        reglement.setCompte(autreCompte);
        List<UUID> ids = List.of(facture.getId(), reglement.getId());

        when(compteComptableRepository.findById(compteClient.getId())).thenReturn(Optional.of(compteClient));
        when(ligneEcritureRepository.findAllById(ids)).thenReturn(List.of(facture, reglement));

        assertThatThrownBy(() -> lettrageService.lettrer(entrepriseId,
                new LettrerLignesRequest(compteClient.getId(), ids, false), principal))
                .hasMessageContaining("meme compte");
    }

    @Test
    void refuse_un_compte_non_lettrable() {
        compteClient.setLettrable(false);
        List<UUID> ids = List.of(UUID.randomUUID(), UUID.randomUUID());

        when(compteComptableRepository.findById(compteClient.getId())).thenReturn(Optional.of(compteClient));

        assertThatThrownBy(() -> lettrageService.lettrer(entrepriseId,
                new LettrerLignesRequest(compteClient.getId(), ids, false), principal))
                .hasMessageContaining("pas lettrable");
    }

    @Test
    void refuse_moins_de_deux_lignes() {
        List<UUID> ids = List.of(UUID.randomUUID());

        when(compteComptableRepository.findById(compteClient.getId())).thenReturn(Optional.of(compteClient));

        assertThatThrownBy(() -> lettrageService.lettrer(entrepriseId,
                new LettrerLignesRequest(compteClient.getId(), ids, false), principal))
                .hasMessageContaining("au moins deux");
    }

    @Test
    void refuse_une_ligne_introuvable() {
        LigneEcriture facture = ligne(new BigDecimal("50000"), BigDecimal.ZERO, StatutEcriture.VALIDEE, null);
        List<UUID> ids = List.of(facture.getId(), UUID.randomUUID());

        when(compteComptableRepository.findById(compteClient.getId())).thenReturn(Optional.of(compteClient));
        when(ligneEcritureRepository.findAllById(ids)).thenReturn(List.of(facture));

        assertThatThrownBy(() -> lettrageService.lettrer(entrepriseId,
                new LettrerLignesRequest(compteClient.getId(), ids, false), principal))
                .hasMessageContaining("introuvables");
    }

    @Test
    void refuse_un_compte_dune_autre_entreprise_pour_le_lettrage() {
        when(compteComptableRepository.findById(compteClient.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> lettrageService.lettrer(entrepriseId,
                new LettrerLignesRequest(compteClient.getId(), List.of(UUID.randomUUID(), UUID.randomUUID()), false), principal))
                .hasMessageContaining("Compte introuvable");
    }

    @Test
    void refuse_le_delettrage_sans_lignes_correspondantes() {
        when(compteComptableRepository.findById(compteClient.getId())).thenReturn(Optional.of(compteClient));
        when(ligneEcritureRepository.findByCompteIdAndLettrage(compteClient.getId(), "Z")).thenReturn(List.of());

        assertThatThrownBy(() -> lettrageService.delettrer(entrepriseId, compteClient.getId(), "Z", principal))
                .hasMessageContaining("Aucune ligne lettree");
    }

    @Test
    void accepte_un_groupe_desequilibre_en_lettrage_partiel_et_renvoie_lecart() {
        LigneEcriture facture = ligne(new BigDecimal("50000"), BigDecimal.ZERO, StatutEcriture.VALIDEE, null);
        LigneEcriture reglementPartiel = ligne(BigDecimal.ZERO, new BigDecimal("30000"), StatutEcriture.VALIDEE, null);
        List<UUID> ids = List.of(facture.getId(), reglementPartiel.getId());

        when(compteComptableRepository.findById(compteClient.getId())).thenReturn(Optional.of(compteClient));
        when(ligneEcritureRepository.findAllById(ids)).thenReturn(List.of(facture, reglementPartiel));
        when(ligneEcritureRepository.countDistinctLettrageByCompteId(compteClient.getId())).thenReturn(0L);
        when(ligneEcritureRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        LettrageResultDto resultat = lettrageService.lettrer(entrepriseId,
                new LettrerLignesRequest(compteClient.getId(), ids, true), principal);

        assertThat(resultat.code()).isEqualTo("A");
        assertThat(resultat.ecart()).isEqualByComparingTo("20000");
        assertThat(facture.getLettrage()).isEqualTo("A");
        assertThat(facture.getDateLettrage()).isEqualTo(java.time.LocalDate.now());
        assertThat(reglementPartiel.getLettrage()).isEqualTo("A");
    }

    @Test
    void un_lettrage_integral_renvoie_un_ecart_nul() {
        LigneEcriture facture = ligne(new BigDecimal("50000"), BigDecimal.ZERO, StatutEcriture.VALIDEE, null);
        LigneEcriture reglement = ligne(BigDecimal.ZERO, new BigDecimal("50000"), StatutEcriture.VALIDEE, null);
        List<UUID> ids = List.of(facture.getId(), reglement.getId());

        when(compteComptableRepository.findById(compteClient.getId())).thenReturn(Optional.of(compteClient));
        when(ligneEcritureRepository.findAllById(ids)).thenReturn(List.of(facture, reglement));
        when(ligneEcritureRepository.countDistinctLettrageByCompteId(compteClient.getId())).thenReturn(0L);
        when(ligneEcritureRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        LettrageResultDto resultat = lettrageService.lettrer(entrepriseId,
                new LettrerLignesRequest(compteClient.getId(), ids, false), principal);

        assertThat(resultat.ecart()).isEqualByComparingTo("0");
    }

    @Test
    void lettrage_automatique_apparie_les_montants_exacts_sans_ambiguite() {
        LigneEcriture facture1 = ligne(new BigDecimal("50000"), BigDecimal.ZERO, StatutEcriture.VALIDEE, null);
        LigneEcriture reglement1 = ligne(BigDecimal.ZERO, new BigDecimal("50000"), StatutEcriture.VALIDEE, null);
        // Montant en double des deux cotes (75000) : ambigu, ne doit pas etre auto-lettre.
        LigneEcriture facture2a = ligne(new BigDecimal("75000"), BigDecimal.ZERO, StatutEcriture.VALIDEE, null);
        LigneEcriture facture2b = ligne(new BigDecimal("75000"), BigDecimal.ZERO, StatutEcriture.VALIDEE, null);
        LigneEcriture reglement2 = ligne(BigDecimal.ZERO, new BigDecimal("75000"), StatutEcriture.VALIDEE, null);

        when(compteComptableRepository.findById(compteClient.getId())).thenReturn(Optional.of(compteClient));
        when(ligneEcritureRepository.rechercherNonLettreesValidees(compteClient.getId()))
                .thenReturn(List.of(facture1, reglement1, facture2a, facture2b, reglement2));
        when(ligneEcritureRepository.countDistinctLettrageByCompteId(compteClient.getId())).thenReturn(0L);
        when(ligneEcritureRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        List<LettrageResultDto> resultats = lettrageService.lettrerAutomatiquement(entrepriseId, compteClient.getId(), principal);

        assertThat(resultats).hasSize(1);
        assertThat(resultats.get(0).code()).isEqualTo("A");
        assertThat(resultats.get(0).ecart()).isEqualByComparingTo("0");
        assertThat(facture1.getLettrage()).isEqualTo("A");
        assertThat(reglement1.getLettrage()).isEqualTo("A");
        assertThat(facture2a.getLettrage()).isNull();
        assertThat(facture2b.getLettrage()).isNull();
        assertThat(reglement2.getLettrage()).isNull();
    }

    @Test
    void lettrage_automatique_ne_fait_rien_si_aucune_paire_exacte() {
        LigneEcriture facture = ligne(new BigDecimal("50000"), BigDecimal.ZERO, StatutEcriture.VALIDEE, null);

        when(compteComptableRepository.findById(compteClient.getId())).thenReturn(Optional.of(compteClient));
        when(ligneEcritureRepository.rechercherNonLettreesValidees(compteClient.getId())).thenReturn(List.of(facture));

        List<LettrageResultDto> resultats = lettrageService.lettrerAutomatiquement(entrepriseId, compteClient.getId(), principal);

        assertThat(resultats).isEmpty();
    }

    @Test
    void delettre_les_lignes_dun_code() {
        LigneEcriture l1 = ligne(new BigDecimal("50000"), BigDecimal.ZERO, StatutEcriture.VALIDEE, "A");
        LigneEcriture l2 = ligne(BigDecimal.ZERO, new BigDecimal("50000"), StatutEcriture.VALIDEE, "A");

        when(compteComptableRepository.findById(compteClient.getId())).thenReturn(Optional.of(compteClient));
        when(ligneEcritureRepository.findByCompteIdAndLettrage(compteClient.getId(), "A")).thenReturn(List.of(l1, l2));
        when(ligneEcritureRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        lettrageService.delettrer(entrepriseId, compteClient.getId(), "A", principal);

        assertThat(l1.getLettrage()).isNull();
        assertThat(l2.getLettrage()).isNull();
    }
}
