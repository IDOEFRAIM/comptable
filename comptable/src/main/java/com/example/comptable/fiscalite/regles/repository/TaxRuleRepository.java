package com.example.comptable.fiscalite.regles.repository;

import com.example.comptable.fiscalite.regles.domain.TaxRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Mission P1 (section 31) : la seule requete necessaire aujourd'hui, tant que la table est vide
 * (voir TaxRule) - une requete "regle en vigueur a une date donnee" sera ajoutee quand un vrai
 * consommateur (peuplement verifie + moteur de calcul) en aura besoin, pas avant. */
public interface TaxRuleRepository extends JpaRepository<TaxRule, UUID> {

    List<TaxRule> findByPays(String pays);
}
