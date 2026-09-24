package com.example.comptable.comptabilite.service;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.ModeImportReference;
import com.example.comptable.comptabilite.domain.PlanComptableReference;
import com.example.comptable.comptabilite.repository.CompteComptableRepository;
import com.example.comptable.comptabilite.repository.PlanComptableReferenceRepository;
import com.example.comptable.comptabilite.web.dto.ImporterReferenceRequest;
import com.example.comptable.comptabilite.web.dto.ImporterReferenceResultDto;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.identite.domain.Role;
import com.example.comptable.identite.domain.Utilisateur;
import com.example.comptable.security.UtilisateurPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlanComptableImportServiceTest {

    @Mock
    private EntrepriseAccessService entrepriseAccessService;
    @Mock
    private PlanComptableReferenceRepository planComptableReferenceRepository;
    @Mock
    private CompteComptableRepository compteComptableRepository;

    @InjectMocks
    private PlanComptableImportService service;

    private UUID entrepriseId;
    private Entreprise entreprise;
    private UtilisateurPrincipal principal;

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

        lenient().when(entrepriseAccessService.verifierAcces(entrepriseId, principal)).thenReturn(entreprise);
        lenient().when(compteComptableRepository.save(any())).thenAnswer(inv -> {
            CompteComptable c = inv.getArgument(0);
            if (c.getId() == null) {
                c.setId(UUID.randomUUID());
            }
            return c;
        });
    }

    private PlanComptableReference reference(int id, String numero, short niveau, PlanComptableReference parent) {
        PlanComptableReference r = new PlanComptableReference();
        r.setId(id);
        r.setNumero(numero);
        r.setIntitule("Intitule " + numero);
        r.setNiveau(niveau);
        r.setParent(parent);
        r.setLettrable(false);
        r.setActif(true);
        return r;
    }

    @Test
    void un_compte_deja_importe_nest_jamais_ecrase_ni_duplique() {
        PlanComptableReference reference = reference(1, "601", (short) 1, null);
        CompteComptable dejaImporte = new CompteComptable();
        dejaImporte.setId(UUID.randomUUID());
        dejaImporte.setEntreprise(entreprise);
        dejaImporte.setReference(reference);
        dejaImporte.setNumero("601");
        dejaImporte.setIntitule("Personnalise par le client"); // personnalisation locale

        when(planComptableReferenceRepository.findByActifTrue()).thenReturn(List.of(reference));
        when(compteComptableRepository.findByEntrepriseIdAndReferenceId(entrepriseId, 1))
                .thenReturn(Optional.of(dejaImporte));

        service.importerTout(entreprise);

        verify(compteComptableRepository, never()).save(any());
    }

    @Test
    void mode_tout_importe_tous_les_comptes_actifs_avec_leur_hierarchie() {
        PlanComptableReference classe = reference(1, "60", (short) 1, null);
        PlanComptableReference enfant = reference(2, "601", (short) 2, classe);

        when(planComptableReferenceRepository.findByActifTrue()).thenReturn(List.of(enfant, classe)); // ordre volontairement inverse
        when(compteComptableRepository.findByEntrepriseIdAndReferenceId(entrepriseId, 1)).thenReturn(Optional.empty());
        when(compteComptableRepository.findByEntrepriseIdAndReferenceId(entrepriseId, 2)).thenReturn(Optional.empty());
        when(compteComptableRepository.findByEntrepriseIdAndNumero(entrepriseId, "60")).thenReturn(Optional.empty());
        when(compteComptableRepository.findByEntrepriseIdAndNumero(entrepriseId, "601")).thenReturn(Optional.empty());

        ImporterReferenceResultDto resultat = service.importer(entrepriseId,
                new ImporterReferenceRequest(ModeImportReference.TOUT, null), principal);

        assertThat(resultat.comptesAjoutes()).containsExactlyInAnyOrder("60", "601");
        assertThat(resultat.comptesIgnores()).isEmpty();

        ArgumentCaptor<CompteComptable> captor = ArgumentCaptor.forClass(CompteComptable.class);
        verify(compteComptableRepository, times(2)).save(captor.capture());
        CompteComptable compteEnfantSauvegarde = captor.getAllValues().stream()
                .filter(c -> "601".equals(c.getNumero())).findFirst().orElseThrow();
        // La feuille est bien rattachee au compte local du parent, malgre l'ordre d'entree inverse -
        // preuve que le tri par niveau croissant garantit que le parent existe deja.
        assertThat(compteEnfantSauvegarde.getParent()).isNotNull();
        assertThat(compteEnfantSauvegarde.getParent().getNumero()).isEqualTo("60");
    }

    @Test
    void mode_selection_inclut_automatiquement_les_ancetres_non_selectionnes() {
        PlanComptableReference classe = reference(1, "60", (short) 1, null);
        PlanComptableReference enfant = reference(2, "601", (short) 2, classe);

        when(planComptableReferenceRepository.findByNumero("601")).thenReturn(Optional.of(enfant));
        when(compteComptableRepository.findByEntrepriseIdAndReferenceId(entrepriseId, 1)).thenReturn(Optional.empty());
        when(compteComptableRepository.findByEntrepriseIdAndReferenceId(entrepriseId, 2)).thenReturn(Optional.empty());
        when(compteComptableRepository.findByEntrepriseIdAndNumero(entrepriseId, "60")).thenReturn(Optional.empty());
        when(compteComptableRepository.findByEntrepriseIdAndNumero(entrepriseId, "601")).thenReturn(Optional.empty());

        // Seul "601" est demande explicitement ; "60" n'apparait dans aucune liste de codes.
        ImporterReferenceResultDto resultat = service.importer(entrepriseId,
                new ImporterReferenceRequest(ModeImportReference.SELECTION, List.of("601")), principal);

        assertThat(resultat.comptesAjoutes()).containsExactlyInAnyOrder("60", "601");
    }

    @Test
    void ignore_avec_raison_une_reference_en_collision_avec_un_compte_local_existant() {
        PlanComptableReference reference = reference(1, "601", (short) 1, null);
        CompteComptable compteLocalExistant = new CompteComptable();
        compteLocalExistant.setId(UUID.randomUUID());
        compteLocalExistant.setEntreprise(entreprise);
        compteLocalExistant.setNumero("601"); // meme numero, mais aucune reference (compte cree manuellement)

        when(planComptableReferenceRepository.findByActifTrue()).thenReturn(List.of(reference));
        when(compteComptableRepository.findByEntrepriseIdAndReferenceId(entrepriseId, 1)).thenReturn(Optional.empty());
        when(compteComptableRepository.findByEntrepriseIdAndNumero(entrepriseId, "601"))
                .thenReturn(Optional.of(compteLocalExistant));

        ImporterReferenceResultDto resultat = service.importer(entrepriseId,
                new ImporterReferenceRequest(ModeImportReference.TOUT, null), principal);

        assertThat(resultat.comptesAjoutes()).isEmpty();
        assertThat(resultat.comptesIgnores()).hasSize(1);
        assertThat(resultat.comptesIgnores().get(0).numero()).isEqualTo("601");
        assertThat(resultat.comptesIgnores().get(0).raison()).isNotBlank();
        verify(compteComptableRepository, never()).save(any());
    }
}
