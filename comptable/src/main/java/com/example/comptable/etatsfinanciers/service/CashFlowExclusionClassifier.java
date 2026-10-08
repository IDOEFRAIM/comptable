package com.example.comptable.etatsfinanciers.service;

import com.example.comptable.etatsfinanciers.definition.MappingRole;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Classification d'un compte de creance/dette pour les variations FB, FD et FE du tableau des flux de tresorerie. La source (p. 1009-1010) donne une regle generale et des exemples
 * (dettes et creances sur cession et acquisition d'immobilisations, creances sur subventions d'investissement, apporteurs sur capital, versements restant sur titres de placement
 * non liberes, ecarts de conversion d'exploitation) mais PAS de liste exhaustive : cette classe ne code que les categories NOMMEES par la source (rattachees aux comptes par le
 * libelle officiel du plan, {@code DERIVED_FROM_PRIMARY}) et les comptes d'exploitation listes par les Notes 7, 17 et 18. Tout autre compte est {@link Classe#UNCLASSIFIED} : il n'est
 * JAMAIS inclus par defaut ; une variation non nulle sur un compte non classifie rend la ligne du TFT incomplete.
 */
@Component
public class CashFlowExclusionClassifier {

    public enum Classe {
        /** Variation du besoin de financement liee aux activites operationnelles : incluse dans FB, FD ou FE. */
        OPERATING,
        /** Dettes et creances sur cession/acquisition d'immobilisations : exclue (prise en compte dans FF a FJ). */
        EXCLUDED_INVESTING,
        /** Creances/dettes rattachees au financement (apporteurs, dividendes, subventions d'investissement, titres non liberes) : exclue (FK a FQ). */
        EXCLUDED_FINANCING,
        /** Provisions et depreciations : variation sans flux. */
        EXCLUDED_NON_CASH,
        /** Ecarts de conversion d'exploitation : exclus. */
        EXCLUDED_CONVERSION,
        /** Aucune regle de la source ne la classe : jamais incluse par defaut. */
        UNCLASSIFIED
    }

    public record Classification(Classe classe, String fondement, boolean dette) {
    }

    private record Regle(List<String> prefixes, Classe classe, String fondement, boolean dette) {
    }

    private static final List<Regle> REGLES = List.of(
            new Regle(List.of("414", "485"), Classe.EXCLUDED_INVESTING,
                    "p.1009 : variations liees aux dettes et creances sur cession et acquisition d'immobilisations (libelles du plan : 404, 414, 481, 482, 485)", false),
            new Regle(List.of("404", "481", "482"), Classe.EXCLUDED_INVESTING, "p.1009 : dettes sur acquisition d'immobilisations (404, 481, 482)", true),
            new Regle(List.of("4494"), Classe.EXCLUDED_FINANCING, "p.1009 : variation de creances liee aux subventions d'investissement (4494 « subventions d'investissement a recevoir »)", false),
            new Regle(List.of("461", "467"), Classe.EXCLUDED_FINANCING, "p.1010 : variation des apporteurs sur le capital et des apporteurs restant du sur capital appele", false),
            new Regle(List.of("4726"), Classe.EXCLUDED_FINANCING, "p.1010 : variation du versement restant a effectuer sur titres de placement non liberes", false),
            new Regle(List.of("465"), Classe.EXCLUDED_FINANCING, "p.1013 : distributions de dividendes (financement) ; 465 « associes, dividendes a payer »", false),
            new Regle(List.of("478", "479"), Classe.EXCLUDED_CONVERSION, "p.1009 : variation des ecarts de conversion d'exploitation", false),
            new Regle(List.of("409", "401", "402", "408", "411", "412", "413", "415", "416", "418", "419", "42", "43", "441", "442", "443", "444", "445", "446", "447", "448", "476", "477"),
                    Classe.OPERATING, "Notes 7, 17, 18 (clients, fournisseurs, dettes sociales et fiscales) et regle generale p.1010 (rattachables a un compte de charge ou de produit retenu pour la CAFG ; 476/477 : regularisations)", false));

    public Classification classer(String numeroCompte, MappingRole role) {
        if (role == MappingRole.DEPRECIATION || numeroCompte.startsWith("49")) {
            return new Classification(Classe.EXCLUDED_NON_CASH, "provisions et depreciations : variation non monetaire", false);
        }
        for (Regle regle : REGLES) {
            if (regle.prefixes().stream().anyMatch(numeroCompte::startsWith)) {
                return new Classification(regle.classe(), regle.fondement(), regle.dette());
            }
        }
        return new Classification(Classe.UNCLASSIFIED, "aucune regle de la source ne classe ce compte", false);
    }

    /** Role d'un compte de gestion (classes 6, 7, 8) dans la CAFG ; les comptes de l'EBE sont determines par le compte de resultat (ligne XD), pas ici. */
    public enum TermeCafg {
        /** + valeurs comptables des cessions courantes (654), p.1007. */
        VNC_CESSION,
        /** - produits des cessions courantes (754), p.1007. */
        PRODUIT_CESSION,
        /** + transferts de charges financieres (787). */
        TRANSFERT_FINANCIER,
        /** + transferts de charges HAO (848). */
        TRANSFERT_HAO,
        /** - participation des travailleurs (87). */
        PARTICIPATION,
        /** - impot sur les resultats (89). */
        IMPOT,
        /** Cessions d'immobilisations HAO (81, 82) : hors CAFG, relevent de FI/FJ. */
        CESSION_HAO,
        /** Dotations et reprises (68, 69, 78 sauf 787, 79, 85, 86) : sans flux, hors CAFG. */
        NON_CASH,
        /** Terme de la CAFG dont le rattachement aux comptes est PARTIAL dans la source : revenus/frais financiers, gains/pertes de change, produits HAO (67, 77, 83, 84, 88). */
        PARTIEL,
        /** Aucune regle : jamais inclus par defaut. */
        INCLASSE
    }

    public TermeCafg termeCafg(String compte) {
        if (compte.startsWith("654")) {
            return TermeCafg.VNC_CESSION;
        }
        if (compte.startsWith("754")) {
            return TermeCafg.PRODUIT_CESSION;
        }
        if (compte.startsWith("787")) {
            return TermeCafg.TRANSFERT_FINANCIER;
        }
        if (compte.startsWith("848")) {
            return TermeCafg.TRANSFERT_HAO;
        }
        if (compte.startsWith("87")) {
            return TermeCafg.PARTICIPATION;
        }
        if (compte.startsWith("89")) {
            return TermeCafg.IMPOT;
        }
        if (compte.startsWith("81") || compte.startsWith("82")) {
            return TermeCafg.CESSION_HAO;
        }
        if (compte.startsWith("849") || compte.startsWith("68") || compte.startsWith("69") || compte.startsWith("78") || compte.startsWith("79")
                || compte.startsWith("85") || compte.startsWith("86")) {
            return TermeCafg.NON_CASH;
        }
        if (compte.startsWith("67") || compte.startsWith("77") || compte.startsWith("83") || compte.startsWith("84") || compte.startsWith("88")) {
            return TermeCafg.PARTIEL;
        }
        return TermeCafg.INCLASSE;
    }
}
