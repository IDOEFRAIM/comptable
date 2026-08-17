package com.example.comptable.comptabilite.service;

/**
 * Leve quand {@link ComptabilisationService#comptabiliserAutomatiquement} ne peut pas produire
 * une ecriture de maniere fiable. {@link EcritureNode} l'attrape et laisse le document en attente
 * de comptabilisation manuelle plutot que de propager une erreur qui ferait echouer le graphe :
 * ce n'est pas une panne, c'est un etat metier normal tant que le pipeline ne sait pas determiner
 * le tiers et le compte de charge/produit sans intervention humaine (cf. {@code ComptabilisationService#comptabiliser}
 * qui exige deja ces deux choix pour la voie manuelle - un humain, pas une regle, decide "quelle
 * depense/quel client" une piece represente).
 */
public class ComptabilisationAutomatiqueIndisponibleException extends RuntimeException {
    public ComptabilisationAutomatiqueIndisponibleException(String message) {
        super(message);
    }
}
