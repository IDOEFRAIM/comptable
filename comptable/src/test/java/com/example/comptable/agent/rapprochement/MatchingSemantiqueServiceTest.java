package com.example.comptable.agent.rapprochement;

import com.example.comptable.agent.client.AiServiceClient;
import com.example.comptable.agent.rapprochement.dto.RapprochementSemantiqueRequestDTO;
import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.EcritureComptable;
import com.example.comptable.comptabilite.domain.LigneEcriture;
import com.example.comptable.comptabilite.domain.LigneReleve;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Depuis la migration vers ai-service, {@link MatchingSemantiqueService} n'est plus qu'un fin
 * assembleur de requete + garde-fou {@code actif} : le prompt, l'appel modele et le garde-fou de
 * confiance/direction/unicite vivent desormais cote Python (testes la-bas, cf.
 * ai-service/tests/), ai-service ne renvoyant deja que des appariements retenus. Ce test ne
 * couvre donc que ce qui reste en Java : le flag, les cas degeneres, et la construction correcte
 * de la requete envoyee a {@link AiServiceClient}.
 */
@ExtendWith(MockitoExtension.class)
class MatchingSemantiqueServiceTest {

    @Mock
    private AiServiceClient aiServiceClient;

    private LigneReleve ligneReleve;
    private LigneEcriture ligneEcriture;

    @BeforeEach
    void setUp() {
        ligneReleve = new LigneReleve(LocalDate.of(2027, 1, 5), "WAVE P2P KOUASSI A", new BigDecimal("15000"));
        ligneReleve.setId(UUID.randomUUID());

        CompteComptable compte = new CompteComptable();
        compte.setId(UUID.randomUUID());

        EcritureComptable ecriture = new EcritureComptable();
        ecriture.setDateEcriture(LocalDate.of(2027, 1, 5));

        ligneEcriture = new LigneEcriture();
        ligneEcriture.setId(UUID.randomUUID());
        ligneEcriture.setEcriture(ecriture);
        ligneEcriture.setCompte(compte);
        ligneEcriture.setLibelle("Vente au comptant");
        ligneEcriture.setDebit(new BigDecimal("15000"));
        ligneEcriture.setCredit(BigDecimal.ZERO);
    }

    @Test
    void ignore_les_propositions_quand_le_service_est_desactive() {
        MatchingSemantiqueService serviceInactif = new MatchingSemantiqueService(aiServiceClient, false);

        List<AppariementSemantique> retenus = serviceInactif.proposerAppariements(
                List.of(ligneReleve), List.of(ligneEcriture));

        assertThat(retenus).isEmpty();
        verifyNoInteractions(aiServiceClient);
    }

    @Test
    void nappelle_pas_ai_service_si_aucune_ligne_ou_aucun_candidat() {
        MatchingSemantiqueService service = new MatchingSemantiqueService(aiServiceClient, true);

        assertThat(service.proposerAppariements(List.of(), List.of(ligneEcriture))).isEmpty();
        assertThat(service.proposerAppariements(List.of(ligneReleve), List.of())).isEmpty();
        verify(aiServiceClient, never()).rapprocherSemantiquement(any());
    }

    @Test
    void delegue_a_ai_service_et_retourne_directement_ses_appariements_quand_actif() {
        MatchingSemantiqueService service = new MatchingSemantiqueService(aiServiceClient, true);
        AppariementSemantique attendu = new AppariementSemantique(ligneReleve.getId(), ligneEcriture.getId(),
                0.95, "Nom du tiers reconnaissable malgre l'abreviation");
        when(aiServiceClient.rapprocherSemantiquement(any())).thenReturn(List.of(attendu));

        List<AppariementSemantique> retenus = service.proposerAppariements(List.of(ligneReleve), List.of(ligneEcriture));

        assertThat(retenus).containsExactly(attendu);

        ArgumentCaptor<RapprochementSemantiqueRequestDTO> captor =
                ArgumentCaptor.forClass(RapprochementSemantiqueRequestDTO.class);
        verify(aiServiceClient).rapprocherSemantiquement(captor.capture());
        assertThat(captor.getValue().lignesNonRapprochees()).hasSize(1);
        assertThat(captor.getValue().lignesNonRapprochees().get(0).id()).isEqualTo(ligneReleve.getId());
        assertThat(captor.getValue().candidats()).hasSize(1);
        assertThat(captor.getValue().candidats().get(0).id()).isEqualTo(ligneEcriture.getId());
        assertThat(captor.getValue().candidats().get(0).debit()).isEqualByComparingTo("15000");
    }
}
