package com.example.comptable.comptabilite.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class EcritureComptableTest {

    private CompteComptable compte(String numero) {
        CompteComptable compte = new CompteComptable();
        compte.setNumero(numero);
        compte.setIntitule("Compte " + numero);
        return compte;
    }

    @Test
    void une_ecriture_dachat_avec_tva_est_equilibree() {
        EcritureComptable ecriture = new EcritureComptable();
        ecriture.ajouterLigne(LigneEcriture.debit(compte("601"), new BigDecimal("100000"), "Achat"));
        ecriture.ajouterLigne(LigneEcriture.debit(compte("4456"), new BigDecimal("18000"), "TVA deductible"));
        ecriture.ajouterLigne(LigneEcriture.credit(compte("401001"), new BigDecimal("118000"), "Fournisseur"));

        assertThat(ecriture.estEquilibree()).isTrue();
        assertThat(ecriture.totalDebit()).isEqualByComparingTo("118000");
        assertThat(ecriture.totalCredit()).isEqualByComparingTo("118000");
    }

    @Test
    void une_ecriture_de_vente_sans_tva_est_equilibree() {
        EcritureComptable ecriture = new EcritureComptable();
        ecriture.ajouterLigne(LigneEcriture.debit(compte("411001"), new BigDecimal("50000"), "Client"));
        ecriture.ajouterLigne(LigneEcriture.credit(compte("701"), new BigDecimal("50000"), "Vente"));

        assertThat(ecriture.estEquilibree()).isTrue();
    }

    @Test
    void une_ecriture_desequilibree_est_detectee() {
        EcritureComptable ecriture = new EcritureComptable();
        ecriture.ajouterLigne(LigneEcriture.debit(compte("601"), new BigDecimal("100000"), "Achat"));
        ecriture.ajouterLigne(LigneEcriture.credit(compte("401001"), new BigDecimal("99999"), "Fournisseur"));

        assertThat(ecriture.estEquilibree()).isFalse();
    }

    @Test
    void une_ecriture_sans_lignes_nest_pas_consideree_equilibree() {
        EcritureComptable ecriture = new EcritureComptable();

        assertThat(ecriture.estEquilibree()).isFalse();
    }

    @Test
    void ajouterLigne_numerote_les_lignes_dans_lordre() {
        EcritureComptable ecriture = new EcritureComptable();
        LigneEcriture ligne1 = LigneEcriture.debit(compte("601"), BigDecimal.TEN, "l1");
        LigneEcriture ligne2 = LigneEcriture.credit(compte("401001"), BigDecimal.TEN, "l2");

        ecriture.ajouterLigne(ligne1);
        ecriture.ajouterLigne(ligne2);

        assertThat(ligne1.getNumeroLigne()).isEqualTo(1);
        assertThat(ligne2.getNumeroLigne()).isEqualTo(2);
    }
}
