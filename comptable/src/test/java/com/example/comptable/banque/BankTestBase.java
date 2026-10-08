package com.example.comptable.banque;

import com.example.comptable.collecte.CollecteTestBase;
import com.example.comptable.comptabilite.operation.common.AccountingOperationType;
import com.example.comptable.comptabilite.operation.common.MoyenPaiement;
import com.example.comptable.comptabilite.operation.reglement.ReglementOperationRequest;
import com.example.comptable.comptabilite.web.dto.TiersDto;
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;

/** Outils communs des tests bancaires : reglements fournisseur comptabilises par le moteur, factures d'achat, releves CSV. */
public abstract class BankTestBase extends CollecteTestBase {

    /** Un reglement fournisseur par virement : ecriture « debit 401 / credit banque ». */
    public record Reglement(UUID ecritureId, UUID ligneBanqueId, UUID compteBanqueId, UUID ligneTiersId) {
    }

    protected Reglement reglement(Dossier d, int montant, LocalDate date, String libelle) {
        return reglement(d, d.fournisseur(), montant, date, libelle);
    }

    protected Reglement reglement(Dossier d, TiersDto fournisseur, int montant, LocalDate date, String libelle) {
        UUID ecriture = engine.valider(d.id(), engine.proposerReglement(d.id(), new ReglementOperationRequest(
                AccountingOperationType.REGLEMENT_FOURNISSEUR, fournisseur.id(), date, "VIR-" + UUID.randomUUID().toString().substring(0, 6),
                libelle, new BigDecimal(montant), MoyenPaiement.VIREMENT), d.expert()).id(), d.expert()).ecritureId();
        UUID ligneBanque = jdbc.queryForObject("select l.id from ligne_ecriture l join compte_comptable c on c.id = l.compte_id "
                + "where l.ecriture_id = ? and c.numero not like '40%'", UUID.class, ecriture);
        UUID compte = jdbc.queryForObject("select compte_id from ligne_ecriture where id = ?", UUID.class, ligneBanque);
        return new Reglement(ecriture, ligneBanque, compte, ligneTiers(ecriture, "debit"));
    }

    /** Ligne 401 (credit) d'une facture d'achat comptabilisee sans piece. */
    protected UUID factureAchat(Dossier d, int montant) {
        return ligneTiers(acheterSansPiece(d, montant), "credit");
    }

    protected UUID ligneTiers(UUID ecritureId, String cote) {
        return jdbc.queryForObject("select l.id from ligne_ecriture l join compte_comptable c on c.id = l.compte_id where l.ecriture_id = ? "
                + "and c.numero like '40%' and l." + cote + " > 0", UUID.class, ecritureId);
    }

    protected MockMultipartFile csv(String... lignes) {
        return new MockMultipartFile("fichier", "releve.csv", "text/csv",
                ("date;libelle;montant\n" + String.join("\n", lignes) + "\n").getBytes(StandardCharsets.UTF_8));
    }
}
