package com.example.comptable.fiscalite.regles.repository;

import com.example.comptable.fiscalite.regles.domain.TaxRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TaxRuleRepository extends JpaRepository<TaxRule, UUID> {

    List<TaxRule> findByPays(String pays);

    /** Consomme par TvaRateResolver (moteur d'operations) : le filtrage par date d'effet se fait
     * en Java (volume minuscule par pays/code), pas via une requete JPQL a intervalle. */
    List<TaxRule> findByPaysAndCode(String pays, String code);
}
