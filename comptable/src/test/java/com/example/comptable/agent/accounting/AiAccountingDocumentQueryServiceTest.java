package com.example.comptable.agent.accounting;

import com.example.comptable.agent.accounting.web.dto.DocumentExtractionSnapshotDto;
import com.example.comptable.document.domain.Document;
import com.example.comptable.document.domain.TypeDocument;
import com.example.comptable.document.repository.DocumentRepository;
import com.example.comptable.entreprise.domain.Entreprise;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiAccountingDocumentQueryServiceTest {

    @Mock
    private DocumentRepository documentRepository;

    private final UUID documentId = UUID.randomUUID();
    private final UUID entrepriseId = UUID.randomUUID();

    @Test
    void document_introuvable_est_refuse() {
        AiAccountingDocumentQueryService service = new AiAccountingDocumentQueryService(documentRepository);
        when(documentRepository.findById(documentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.obtenirExtraction(documentId))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
    }

    @Test
    void extraction_deja_persistee_est_relue_sans_relancer_de_calcul() {
        AiAccountingDocumentQueryService service = new AiAccountingDocumentQueryService(documentRepository);

        Entreprise entreprise = new Entreprise();
        entreprise.setId(entrepriseId);

        Document document = new Document();
        document.setId(documentId);
        document.setEntreprise(entreprise);
        document.setTypeDocument(TypeDocument.FACTURE_ACHAT);
        document.setMontantHt(new BigDecimal("100000"));
        document.setMontantTva(new BigDecimal("18000"));
        document.setMontantTtc(new BigDecimal("118000"));
        document.setDatePiece(LocalDate.of(2026, 8, 1));
        document.setDonneesOcr(Map.of("fournisseur", "Station Total", "devise", "XOF", "modePaiement", "VIREMENT", "confiance", 0.95));

        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));

        DocumentExtractionSnapshotDto snapshot = service.obtenirExtraction(documentId);

        assertThat(snapshot.documentId()).isEqualTo(documentId);
        assertThat(snapshot.companyId()).isEqualTo(entrepriseId);
        assertThat(snapshot.typeDocument()).isEqualTo(TypeDocument.FACTURE_ACHAT);
        assertThat(snapshot.montantHt()).isEqualByComparingTo("100000");
        assertThat(snapshot.montantTva()).isEqualByComparingTo("18000");
        assertThat(snapshot.montantTtc()).isEqualByComparingTo("118000");
        assertThat(snapshot.fournisseur()).isEqualTo("Station Total");
        assertThat(snapshot.devise()).isEqualTo("XOF");
        assertThat(snapshot.modePaiement()).isEqualTo("VIREMENT");
        assertThat(snapshot.confiance()).isEqualTo(0.95);
    }

    @Test
    void donnees_ocr_absentes_ne_provoque_aucune_exception_devise_par_defaut_xof() {
        AiAccountingDocumentQueryService service = new AiAccountingDocumentQueryService(documentRepository);

        Entreprise entreprise = new Entreprise();
        entreprise.setId(entrepriseId);

        Document document = new Document();
        document.setId(documentId);
        document.setEntreprise(entreprise);
        document.setTypeDocument(TypeDocument.AUTRE);
        document.setMontantTtc(new BigDecimal("5000"));
        document.setDonneesOcr(null);

        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));

        DocumentExtractionSnapshotDto snapshot = service.obtenirExtraction(documentId);

        assertThat(snapshot.fournisseur()).isNull();
        assertThat(snapshot.devise()).isEqualTo("XOF");
        assertThat(snapshot.confiance()).isNull();
    }
}
