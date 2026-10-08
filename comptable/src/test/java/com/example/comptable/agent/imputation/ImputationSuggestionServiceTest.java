package com.example.comptable.agent.imputation;

import com.example.comptable.agent.imputation.domain.ImputationSuggestion;
import com.example.comptable.agent.imputation.domain.StatutImputationSuggestion;
import com.example.comptable.agent.imputation.repository.ImputationSuggestionRepository;
import com.example.comptable.agent.imputation.web.dto.ImputationSuggestionDto;
import com.example.comptable.document.domain.Document;
import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.identite.domain.Role;
import com.example.comptable.identite.domain.Utilisateur;
import com.example.comptable.identite.repository.UtilisateurRepository;
import com.example.comptable.security.UtilisateurPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Mission P1.5 (section 51) : couverture de test manquante, confirmee par l'audit ("zero UI, zero
 * test au niveau service" - voir docs/P1_5_ARCHITECTURE.md), pour la validation humaine des
 * {@link ImputationSuggestion} (accepter/rejeter/idempotence/isolation tenant).
 */
@ExtendWith(MockitoExtension.class)
class AccountingProposalServiceTest {

    @Mock
    private ImputationSuggestionRepository imputationSuggestionRepository;
    @Mock
    private EntrepriseAccessService entrepriseAccessService;
    @Mock
    private UtilisateurRepository utilisateurRepository;

    @InjectMocks
    private ImputationSuggestionService imputationSuggestionService;

    private UUID entrepriseId;
    private Entreprise entreprise;
    private UtilisateurPrincipal principal;
    private Utilisateur utilisateur;

    @BeforeEach
    void setUp() {
        entrepriseId = UUID.randomUUID();
        entreprise = new Entreprise();
        entreprise.setId(entrepriseId);

        Role role = new Role();
        role.setCode("COLLABORATEUR");
        utilisateur = new Utilisateur();
        utilisateur.setId(UUID.randomUUID());
        utilisateur.setEmail("collab@cabinet.ci");
        utilisateur.setMotDePasse("hash");
        utilisateur.setNom("Ouedraogo");
        utilisateur.setPrenom("Fatou");
        utilisateur.setRoles(Set.of(role));
        principal = new UtilisateurPrincipal(utilisateur);

        lenient().when(entrepriseAccessService.verifierAcces(entrepriseId, principal)).thenReturn(entreprise);
        lenient().when(utilisateurRepository.getReferenceById(principal.getId())).thenReturn(utilisateur);
    }

    private ImputationSuggestion propositionEnAttente(UUID entreprisePourProposition) {
        Entreprise e = new Entreprise();
        e.setId(entreprisePourProposition);
        Document document = new Document();
        document.setId(UUID.randomUUID());

        ImputationSuggestion proposition = new ImputationSuggestion();
        proposition.setId(UUID.randomUUID());
        proposition.setEntreprise(e);
        proposition.setDocument(document);
        proposition.setConfidence(0.8);
        proposition.setStatus(StatutImputationSuggestion.PROPOSED);
        return proposition;
    }

    @Test
    void accepter_valide_la_proposition_sans_comptabiliser() {
        ImputationSuggestion proposition = propositionEnAttente(entrepriseId);
        when(imputationSuggestionRepository.findById(proposition.getId())).thenReturn(Optional.of(proposition));
        when(imputationSuggestionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ImputationSuggestionDto dto = imputationSuggestionService.accepter(entrepriseId, proposition.getId(), principal);

        assertThat(dto.status()).isEqualTo("ACCEPTED");
        ArgumentCaptor<ImputationSuggestion> captor = ArgumentCaptor.forClass(ImputationSuggestion.class);
        verify(imputationSuggestionRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(StatutImputationSuggestion.ACCEPTED);
        assertThat(captor.getValue().getValidatedAt()).isNotNull();
        assertThat(captor.getValue().getValidatedBy()).isEqualTo(utilisateur);
    }

    @Test
    void rejeter_marque_la_proposition_rejetee() {
        ImputationSuggestion proposition = propositionEnAttente(entrepriseId);
        when(imputationSuggestionRepository.findById(proposition.getId())).thenReturn(Optional.of(proposition));
        when(imputationSuggestionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ImputationSuggestionDto dto = imputationSuggestionService.rejeter(entrepriseId, proposition.getId(), "wrong_supplier", principal);

        assertThat(dto.status()).isEqualTo("REJECTED");
    }

    @Test
    void accepter_une_proposition_deja_traitee_echoue_409() {
        ImputationSuggestion proposition = propositionEnAttente(entrepriseId);
        proposition.setStatus(StatutImputationSuggestion.ACCEPTED);
        when(imputationSuggestionRepository.findById(proposition.getId())).thenReturn(Optional.of(proposition));

        assertThatThrownBy(() -> imputationSuggestionService.accepter(entrepriseId, proposition.getId(), principal))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("deja ete traitee");
        verify(imputationSuggestionRepository, never()).save(any());
    }

    @Test
    void accepter_une_proposition_dune_autre_entreprise_echoue_404() {
        ImputationSuggestion proposition = propositionEnAttente(UUID.randomUUID());
        when(imputationSuggestionRepository.findById(proposition.getId())).thenReturn(Optional.of(proposition));

        assertThatThrownBy(() -> imputationSuggestionService.accepter(entrepriseId, proposition.getId(), principal))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("introuvable");
        verify(imputationSuggestionRepository, never()).save(any());
    }

    @Test
    void accepter_une_proposition_inexistante_echoue_404() {
        UUID propositionId = UUID.randomUUID();
        when(imputationSuggestionRepository.findById(propositionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> imputationSuggestionService.accepter(entrepriseId, propositionId, principal))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("introuvable");
    }

    @Test
    void obtenirPourDocument_ne_retourne_que_la_proposition_en_attente() {
        UUID documentId = UUID.randomUUID();
        ImputationSuggestion proposition = propositionEnAttente(entrepriseId);
        when(imputationSuggestionRepository.findByDocumentIdAndStatus(documentId, StatutImputationSuggestion.PROPOSED))
                .thenReturn(Optional.of(proposition));

        Optional<ImputationSuggestionDto> dto = imputationSuggestionService.obtenirPourDocument(entrepriseId, documentId, principal);

        assertThat(dto).isPresent();
        assertThat(dto.get().status()).isEqualTo("PROPOSED");
    }

    @Test
    void obtenirPourDocument_retourne_vide_si_aucune_proposition_en_attente() {
        UUID documentId = UUID.randomUUID();
        when(imputationSuggestionRepository.findByDocumentIdAndStatus(documentId, StatutImputationSuggestion.PROPOSED))
                .thenReturn(Optional.empty());

        Optional<ImputationSuggestionDto> dto = imputationSuggestionService.obtenirPourDocument(entrepriseId, documentId, principal);

        assertThat(dto).isEmpty();
    }
}
