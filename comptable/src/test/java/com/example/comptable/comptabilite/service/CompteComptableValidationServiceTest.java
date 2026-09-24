package com.example.comptable.comptabilite.service;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.PlanComptableReference;
import com.example.comptable.comptabilite.repository.CompteComptableRepository;
import com.example.comptable.entreprise.domain.Entreprise;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompteComptableValidationServiceTest {

    @Mock
    private CompteComptableRepository compteComptableRepository;

    @InjectMocks
    private CompteComptableValidationService service;

    private UUID entrepriseId;
    private Entreprise entreprise;
    private UUID autreEntrepriseId;

    @BeforeEach
    void setUp() {
        entrepriseId = UUID.randomUUID();
        entreprise = new Entreprise();
        entreprise.setId(entrepriseId);
        autreEntrepriseId = UUID.randomUUID();
    }

    private CompteComptable compte(Entreprise ent, boolean actif) {
        CompteComptable compte = new CompteComptable();
        compte.setId(UUID.randomUUID());
        compte.setEntreprise(ent);
        compte.setNumero("601");
        compte.setIntitule("Achats");
        compte.setActif(actif);
        return compte;
    }

    @Test
    void refuse_un_compte_dune_autre_entreprise_avec_404() {
        Entreprise autre = new Entreprise();
        autre.setId(autreEntrepriseId);
        CompteComptable compte = compte(autre, true);
        when(compteComptableRepository.findById(compte.getId())).thenReturn(Optional.of(compte));

        assertThatThrownBy(() -> service.chargerCompteUtilisable(entrepriseId, compte.getId(), false))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void refuse_un_compte_desactive_avec_422() {
        CompteComptable compte = compte(entreprise, false);
        when(compteComptableRepository.findById(compte.getId())).thenReturn(Optional.of(compte));

        assertThatThrownBy(() -> service.chargerCompteUtilisable(entrepriseId, compte.getId(), false))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    @Test
    void refuse_un_compte_non_detail_quand_le_detail_est_exige() {
        PlanComptableReference reference = new PlanComptableReference();
        reference.setNumero("41");
        reference.setCompteDetail(false);

        CompteComptable compte = compte(entreprise, true);
        compte.setReference(reference);
        when(compteComptableRepository.findById(compte.getId())).thenReturn(Optional.of(compte));

        assertThatThrownBy(() -> service.chargerCompteUtilisable(entrepriseId, compte.getId(), true))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    @Test
    void accepte_un_compte_local_feuille_sans_enfant() {
        CompteComptable compte = compte(entreprise, true); // pas de reference : compte local
        when(compteComptableRepository.findById(compte.getId())).thenReturn(Optional.of(compte));
        when(compteComptableRepository.existsByParentId(compte.getId())).thenReturn(false);

        CompteComptable resultat = service.chargerCompteUtilisable(entrepriseId, compte.getId(), true);

        assertThat(resultat).isEqualTo(compte);
    }

    @Test
    void refuse_un_compte_local_qui_a_des_sous_comptes() {
        CompteComptable compte = compte(entreprise, true); // pas de reference : compte local
        when(compteComptableRepository.findById(compte.getId())).thenReturn(Optional.of(compte));
        when(compteComptableRepository.existsByParentId(compte.getId())).thenReturn(true);

        assertThatThrownBy(() -> service.chargerCompteUtilisable(entrepriseId, compte.getId(), true))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }
}
