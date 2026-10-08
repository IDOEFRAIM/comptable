package com.example.comptable.etatsfinanciers.service;

import com.example.comptable.comptabilite.balance.ReportingView;
import com.example.comptable.comptabilite.domain.CashFlowEvent;
import com.example.comptable.comptabilite.domain.EcritureComptable;
import com.example.comptable.comptabilite.domain.LigneEcriture;
import com.example.comptable.comptabilite.operation.common.domain.AccountingOperationProposal;
import com.example.comptable.comptabilite.operation.common.repository.AccountingOperationProposalRepository;
import com.example.comptable.comptabilite.repository.LigneEcritureRepository;
import com.example.comptable.etatsfinanciers.definition.FinancialStatementDefinition;
import com.example.comptable.etatsfinanciers.definition.MappingRole;
import com.example.comptable.etatsfinanciers.mapping.FinancialStatementMappingResolver;
import com.example.comptable.etatsfinanciers.mapping.FinancialStatementMappingResolver.AccountInput;
import com.example.comptable.etatsfinanciers.service.CashFlowExclusionClassifier.Classe;
import com.example.comptable.etatsfinanciers.service.CashFlowExclusionClassifier.Classification;
import com.example.comptable.etatsfinanciers.service.CashFlowExclusionClassifier.TermeCafg;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Flux d'investissement (FF a FJ) et de financement (FK a FQ) du TFT, determines a partir des ECRITURES et de leur preuve de tresorerie, jamais d'un numero de compte seul.
 *
 * <p>Principe : une ecriture sans ligne de tresorerie (comptes mappes BQ, BR, BS, DQ, DR par les definitions du Bilan) n'a aucun effet cash : reclassement, incorporation de reserves,
 * conversion de dette en capital, location-acquisition initiale, mise au rebut, cession non encaissee. Une ecriture avec tresorerie dont les contreparties touchent une famille de flux est
 * soit <b>qualifiee</b> par un {@link CashFlowEvent} declare a la saisie (le montant est celui des lignes de contrepartie de la famille, verifie contre le sens et le montant de la tresorerie),
 * soit une acquisition d'immobilisation payee comptant (debit d'un compte d'actif immobilise et credit de tresorerie dans la meme ecriture : preuve suffisante), soit <b>non qualifiee</b> :
 * les lignes concernees sont alors {@code INCOMPLETE} avec la liste des ecritures, jamais estimees.</p>
 *
 * <p>Le lineage existant (proposition comptable : sourceType / sourceId / sourceEvent) est joint a chaque composant pour le drill-down ; il ne qualifie aucun de ces flux car aucune operation
 * metier existante ne produit d'ecriture de capital, d'emprunt, de dividende, de subvention ou de cession : ces ecritures sont manuelles.</p>
 */
@Component
@RequiredArgsConstructor
public class CashFlowEventResolver {

    static final List<String> LIGNES_INVESTISSEMENT = List.of("FF", "FG", "FH", "FI", "FJ");
    static final List<String> LIGNES_FINANCEMENT = List.of("FK", "FL", "FM", "FN", "FO", "FP", "FQ");

    private static final Set<String> INCORPORELLES = Set.of("AE", "AF", "AG", "AH");
    private static final Set<String> CORPORELLES = Set.of("AJ", "AK", "AL", "AM", "AN", "AP");
    private static final Set<String> FINANCIERES = Set.of("AR", "AS");
    private static final Set<String> CAPITAUX = Set.of("CA", "CB", "CD", "CE", "CF", "CG", "CH", "CL", "CM");
    private static final Set<String> DETTES_FINANCIERES = Set.of("DA", "DB");

    private final LigneEcritureRepository ligneRepository;
    private final com.example.comptable.comptabilite.repository.CompteComptableRepository compteRepository;
    private final AccountingOperationProposalRepository proposalRepository;
    private final FinancialStatementMappingResolver resolver;
    private final CashFlowExclusionClassifier classifier;
    private final StatementComputationCache cache;

    public record Lineage(String operationType, String sourceType, UUID sourceId, String sourceEvent) {
    }

    /** Composant d'un flux : une ecriture, son evenement (declare ou prouve), son montant signe et son lineage. */
    public record Composant(String ligneTft, UUID ecritureId, String numeroPiece, LocalDate date, String evenement, BigDecimal montant, List<Lineage> lineage) {
    }

    public record LigneEcritureInfo(String compte, String libelleCompte, BigDecimal debit, BigDecimal credit) {
    }

    /** Ecriture de tresorerie dont l'evenement n'est pas qualifie (ou l'est de facon incoherente) : de quoi la voir et la qualifier sans autre appel. */
    public record EcritureNonQualifiee(UUID ecritureId, String numeroPiece, LocalDate date, String libelle, BigDecimal tresorerie, List<String> lignesTft, List<String> raisons,
                                       String evenementDeclare, List<LigneEcritureInfo> lignes) {
    }

    private static final class Signal {
        final Set<String> lignes = new LinkedHashSet<>();
        final List<String> raisons = new ArrayList<>();
    }

    public record Resultat(Map<String, BigDecimal> montants, Map<String, List<String>> raisons, List<Composant> composants, Map<String, List<String>> ecrituresNonQualifiees,
                           List<EcritureNonQualifiee> details) {

        public boolean complet(String ligne) {
            return raisons.getOrDefault(ligne, List.of()).isEmpty();
        }
    }

    private enum Famille { CAPITAUX, DETTES, INCORPORELLE, CORPORELLE, FINANCIERE, DETTE_INVESTISSEMENT, CREANCE_CESSION, PRODUIT_CESSION, FINANCEMENT_AUTRE }

    private record Role(boolean tresorerie, Set<Famille> familles) {
    }

    public Resultat resoudre(UUID entrepriseId, LocalDate debut, LocalDate fin, FinancialStatementDefinition defActif, FinancialStatementDefinition defPassif) {
        return cache.enCache(List.of("EVENEMENTS", entrepriseId, debut, fin), () -> resoudreSansCache(entrepriseId, debut, fin, defActif, defPassif));
    }

    private Resultat resoudreSansCache(UUID entrepriseId, LocalDate debut, LocalDate fin, FinancialStatementDefinition defActif, FinancialStatementDefinition defPassif) {
        ReportingView vue = ReportingView.PERIOD_ACTIVITY;
        List<LigneEcriture> lignes = lignesDesEcrituresDeTresorerie(entrepriseId, debut, fin, vue, defActif, defPassif);
        Map<UUID, List<LigneEcriture>> parEcriture = new LinkedHashMap<>();
        for (LigneEcriture l : lignes) {
            parEcriture.computeIfAbsent(l.getEcriture().getId(), k -> new ArrayList<>()).add(l);
        }
        Map<UUID, Role> roles = roles(lignes, defActif, defPassif);

        Map<String, BigDecimal> montants = new LinkedHashMap<>();
        Map<String, List<String>> raisons = new LinkedHashMap<>();
        Map<String, Set<String>> nonQualifiees = new LinkedHashMap<>();
        List<EcritureNonQualifiee> details = new ArrayList<>();
        List<Composant> composants = new ArrayList<>();
        List<String> toutes = new ArrayList<>(LIGNES_INVESTISSEMENT);
        toutes.addAll(LIGNES_FINANCEMENT);
        toutes.forEach(l -> {
            montants.put(l, BigDecimal.ZERO);
            raisons.put(l, new ArrayList<>());
        });

        List<UUID> aTracer = new ArrayList<>();
        Map<UUID, List<Composant>> enAttente = new LinkedHashMap<>();
        for (var entree : parEcriture.entrySet()) {
            List<LigneEcriture> lignesEcriture = entree.getValue();
            EcritureComptable e = lignesEcriture.get(0).getEcriture();
            BigDecimal tresorerie = BigDecimal.ZERO;
            List<LigneEcriture> contreparties = new ArrayList<>();
            for (LigneEcriture l : lignesEcriture) {
                if (roles.get(l.getCompte().getId()).tresorerie()) {
                    tresorerie = tresorerie.add(l.getDebit().subtract(l.getCredit()));
                } else {
                    contreparties.add(l);
                }
            }
            if (tresorerie.signum() == 0) {
                continue;     // aucun effet de tresorerie : pas de flux, quelle que soit la nature des comptes
            }
            Signal signal = new Signal();
            List<Composant> produits = analyser(e, e.getCashFlowEvent(), tresorerie, contreparties, roles, raisons, nonQualifiees, signal);
            if (!signal.lignes.isEmpty()) {
                details.add(new EcritureNonQualifiee(e.getId(), e.getNumeroPiece(), e.getDateEcriture(), e.getLibelle(), tresorerie, List.copyOf(signal.lignes), List.copyOf(signal.raisons),
                        e.getCashFlowEvent() == null ? null : e.getCashFlowEvent().name(),
                        lignesEcriture.stream().map(l -> new LigneEcritureInfo(l.getCompte().getNumero(), l.getCompte().getIntitule(), l.getDebit(), l.getCredit())).toList()));
            }
            if (!produits.isEmpty()) {
                enAttente.put(e.getId(), produits);
                aTracer.add(e.getId());
            }
        }
        Map<UUID, List<Lineage>> lineage = lineage(aTracer);
        for (var entree : enAttente.entrySet()) {
            for (Composant c : entree.getValue()) {
                montants.merge(c.ligneTft(), c.montant(), BigDecimal::add);
                composants.add(new Composant(c.ligneTft(), c.ecritureId(), c.numeroPiece(), c.date(), c.evenement(), c.montant(), lineage.getOrDefault(c.ecritureId(), List.of())));
            }
        }
        Map<String, List<String>> nq = new LinkedHashMap<>();
        nonQualifiees.forEach((k, v) -> nq.put(k, List.copyOf(v)));
        raisons.replaceAll((k, v) -> List.copyOf(v));
        return new Resultat(montants, raisons, composants, nq, List.copyOf(details));
    }

    /**
     * Verifie, avant enregistrement, qu'un evenement declare est coherent avec les lignes de l'ecriture (comptes, sens, presence de tresorerie, montant). Une declaration du frontend ne
     * suffit jamais : retourne la raison du refus, ou vide si l'evenement est acceptable. {@code null} et {@code NO_TFT_FLOW} sont toujours acceptables sur une ecriture de tresorerie.
     */
    public java.util.Optional<String> incoherence(EcritureComptable e, CashFlowEvent evenement, FinancialStatementDefinition defActif, FinancialStatementDefinition defPassif) {
        List<LigneEcriture> lignes = e.getLignes();
        Map<UUID, Role> roles = roles(lignes, defActif, defPassif);
        BigDecimal tresorerie = BigDecimal.ZERO;
        List<LigneEcriture> contreparties = new ArrayList<>();
        for (LigneEcriture l : lignes) {
            if (roles.get(l.getCompte().getId()).tresorerie()) {
                tresorerie = tresorerie.add(l.getDebit().subtract(l.getCredit()));
            } else {
                contreparties.add(l);
            }
        }
        if (tresorerie.signum() == 0) {
            return java.util.Optional.of("L'ecriture ne comporte aucun mouvement de tresorerie : il n'y a pas de flux a qualifier");
        }
        if (evenement == null || evenement == CashFlowEvent.NO_TFT_FLOW) {
            return java.util.Optional.empty();
        }
        Signal signal = new Signal();
        Map<String, List<String>> raisons = new LinkedHashMap<>();
        Map<String, Set<String>> nonQualifiees = new LinkedHashMap<>();
        for (String l : LIGNES_INVESTISSEMENT) {
            raisons.put(l, new ArrayList<>());
        }
        for (String l : LIGNES_FINANCEMENT) {
            raisons.put(l, new ArrayList<>());
        }
        analyser(e, evenement, tresorerie, contreparties, roles, raisons, nonQualifiees, signal);
        return signal.raisons.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(signal.raisons.get(0));
    }

    /**
     * Seules les ecritures touchant un compte de tresorerie peuvent produire un flux : on identifie d'abord les comptes de tresorerie (regles du Bilan, sur les comptes de l'entreprise),
     * puis on ne charge QUE les lignes de ces ecritures (au lieu de toutes les lignes de la periode).
     */
    private List<LigneEcriture> lignesDesEcrituresDeTresorerie(UUID entrepriseId, LocalDate debut, LocalDate fin, ReportingView vue, FinancialStatementDefinition defActif,
                                                              FinancialStatementDefinition defPassif) {
        Set<UUID> comptesTresorerie = comptesDeTresorerie(entrepriseId, defActif, defPassif);
        if (comptesTresorerie.isEmpty()) {
            return List.of();
        }
        List<UUID> ecritureIds = ligneRepository.rechercherEcrituresTouchantLesComptes(entrepriseId, debut, fin, vue.statuts(), vue.natures(), comptesTresorerie);
        List<LigneEcriture> lignes = new ArrayList<>();
        for (int i = 0; i < ecritureIds.size(); i += 5000) {
            lignes.addAll(ligneRepository.rechercherLignesDesEcritures(ecritureIds.subList(i, Math.min(i + 5000, ecritureIds.size()))));
        }
        return lignes;
    }

    private Set<UUID> comptesDeTresorerie(UUID entrepriseId, FinancialStatementDefinition defActif, FinancialStatementDefinition defPassif) {
        List<AccountInput> debiteurs = new ArrayList<>();
        List<AccountInput> crediteurs = new ArrayList<>();
        for (var c : compteRepository.findByEntrepriseId(entrepriseId)) {
            debiteurs.add(new AccountInput(c.getId(), c.getNumero(), c.getIntitule(), BigDecimal.ONE));
            crediteurs.add(new AccountInput(c.getId(), c.getNumero(), c.getIntitule(), BigDecimal.ONE.negate()));
        }
        Set<UUID> ids = new LinkedHashSet<>();
        resolver.resoudre(defActif, debiteurs).mapped().stream().filter(m -> m.role() == MappingRole.GROSS && Set.of("BQ", "BR", "BS").contains(m.lineCode()))
                .forEach(m -> ids.add(m.account().id()));
        resolver.resoudre(defPassif, crediteurs).mapped().stream().filter(m -> Set.of("DQ", "DR").contains(m.lineCode())).forEach(m -> ids.add(m.account().id()));
        return ids;
    }

    // ------------------------------------------------------------------------------------------------ analyse d'une ecriture avec tresorerie

    private List<Composant> analyser(EcritureComptable e, CashFlowEvent evenement, BigDecimal tresorerie, List<LigneEcriture> contreparties, Map<UUID, Role> roles,
                                     Map<String, List<String>> raisons, Map<String, Set<String>> nonQualifiees, Signal signal) {
        String piece = e.getNumeroPiece();
        Set<Famille> presentes = new LinkedHashSet<>();
        for (LigneEcriture l : contreparties) {
            presentes.addAll(roles.get(l.getCompte().getId()).familles());
        }
        if (evenement == CashFlowEvent.NO_TFT_FLOW) {
            return List.of();      // declaration explicite : aucun flux d'investissement ni de financement
        }
        if (evenement != null) {
            String ligne = evenement.ligneTft();
            Set<Famille> famille = famillesDe(evenement);
            BigDecimal somme = BigDecimal.ZERO;
            boolean autres = false;
            for (LigneEcriture l : contreparties) {
                if (roles.get(l.getCompte().getId()).familles().stream().anyMatch(famille::contains)) {
                    somme = somme.add(l.getDebit().subtract(l.getCredit()));
                } else {
                    autres = true;
                }
            }
            BigDecimal montant = somme.negate();
            boolean sensCoherent = evenement.encaissement() ? montant.signum() > 0 : montant.signum() < 0;
            boolean ancre = contreparties.stream().anyMatch(l -> roles.get(l.getCompte().getId()).familles().stream().anyMatch(ancres(evenement)::contains));
            boolean sensTresorerie = evenement.encaissement() ? tresorerie.signum() > 0 : tresorerie.signum() < 0;
            boolean montantCoherent = autres || montant.abs().compareTo(tresorerie.abs()) == 0;
            if (!sensCoherent || !ancre || !sensTresorerie || !montantCoherent) {
                signaler(raisons, nonQualifiees, signal, List.of(ligne), piece, "evenement " + evenement + " incoherent avec les lignes de l'ecriture " + piece);
                return List.of();
            }
            return List.of(new Composant(ligne, e.getId(), piece, e.getDateEcriture(), evenement.name(), montant, List.of()));
        }

        // acquisition payee comptant : debit d'actif immobilise + credit de tresorerie dans la meme ecriture, sans autre famille de flux
        Set<Famille> actifs = new LinkedHashSet<>(presentes);
        actifs.retainAll(Set.of(Famille.INCORPORELLE, Famille.CORPORELLE, Famille.FINANCIERE));
        Set<Famille> autresFamilles = new LinkedHashSet<>(presentes);
        autresFamilles.removeAll(Set.of(Famille.INCORPORELLE, Famille.CORPORELLE, Famille.FINANCIERE));
        if (!actifs.isEmpty() && autresFamilles.isEmpty() && tresorerie.signum() < 0) {
            List<Composant> resultat = new ArrayList<>();
            boolean sortie = false;
            Map<Famille, BigDecimal> parFamille = new HashMap<>();
            for (LigneEcriture l : contreparties) {
                for (Famille f : roles.get(l.getCompte().getId()).familles()) {
                    if (actifs.contains(f)) {
                        parFamille.merge(f, l.getDebit().subtract(l.getCredit()), BigDecimal::add);
                    }
                }
            }
            for (var p : parFamille.entrySet()) {
                if (p.getValue().signum() <= 0) {
                    sortie = true;
                }
            }
            BigDecimal total = parFamille.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            if (!sortie && total.compareTo(tresorerie.negate()) <= 0) {
                for (var p : parFamille.entrySet()) {
                    String ligne = p.getKey() == Famille.INCORPORELLE ? "FF" : p.getKey() == Famille.CORPORELLE ? "FG" : "FH";
                    resultat.add(new Composant(ligne, e.getId(), piece, e.getDateEcriture(), "ACQUISITION_PAYEE_COMPTANT", p.getValue().negate(), List.of()));
                }
                return resultat;
            }
        }

        // famille de flux touchee sans evenement qualifie : jamais estimee
        List<String> touchees = new ArrayList<>();
        if (presentes.contains(Famille.CAPITAUX) || presentes.contains(Famille.FINANCEMENT_AUTRE)) {
            touchees.addAll(List.of("FK", "FL", "FM", "FN"));
        }
        if (presentes.contains(Famille.DETTES)) {
            touchees.addAll(List.of("FO", "FP", "FQ"));
        }
        if (presentes.contains(Famille.DETTE_INVESTISSEMENT) || (!actifs.isEmpty() && tresorerie.signum() < 0)) {
            touchees.addAll(List.of("FF", "FG", "FH"));
        }
        if (presentes.contains(Famille.CREANCE_CESSION) || presentes.contains(Famille.PRODUIT_CESSION) || (!actifs.isEmpty() && tresorerie.signum() > 0)) {
            touchees.addAll(List.of("FI", "FJ"));
        }
        if (!touchees.isEmpty()) {
            signaler(raisons, nonQualifiees, signal, touchees.stream().distinct().toList(), piece, "ecriture " + piece + " : mouvement de tresorerie avec compte d'investissement ou de financement sans evenement qualifie");
        }
        return List.of();
    }

    private static void signaler(Map<String, List<String>> raisons, Map<String, Set<String>> nonQualifiees, Signal signal, List<String> lignes, String piece, String raison) {
        signal.lignes.addAll(lignes);
        if (!signal.raisons.contains(raison)) {
            signal.raisons.add(raison);
        }
        for (String l : lignes) {
            if (!raisons.get(l).contains(raison)) {
                raisons.get(l).add(raison);
            }
            nonQualifiees.computeIfAbsent(l, k -> new TreeSet<>()).add(piece);
        }
    }

    private static Set<Famille> famillesDe(CashFlowEvent e) {
        return switch (e) {
            case CAPITAL_CASH_INCREASE, INVESTMENT_GRANT_CASH_RECEIVED, CAPITAL_CASH_WITHDRAWAL, DIVIDEND_CASH_PAID -> Set.of(Famille.CAPITAUX, Famille.FINANCEMENT_AUTRE);
            case BORROWING_CASH_RECEIVED, OTHER_FINANCIAL_DEBT_CASH_RECEIVED, BORROWING_OR_LEASE_DEBT_CASH_REPAID -> Set.of(Famille.DETTES);
            case INTANGIBLE_ASSET_ACQUISITION_CASH_PAID -> Set.of(Famille.INCORPORELLE, Famille.DETTE_INVESTISSEMENT);
            case TANGIBLE_ASSET_ACQUISITION_CASH_PAID -> Set.of(Famille.CORPORELLE, Famille.DETTE_INVESTISSEMENT);
            case FINANCIAL_ASSET_ACQUISITION_CASH_PAID -> Set.of(Famille.FINANCIERE, Famille.DETTE_INVESTISSEMENT);
            case TANGIBLE_INTANGIBLE_ASSET_SALE_CASH_RECEIVED -> Set.of(Famille.INCORPORELLE, Famille.CORPORELLE, Famille.CREANCE_CESSION, Famille.PRODUIT_CESSION);
            case FINANCIAL_ASSET_SALE_CASH_RECEIVED -> Set.of(Famille.FINANCIERE, Famille.CREANCE_CESSION, Famille.PRODUIT_CESSION);
            case NO_TFT_FLOW -> Set.of();
        };
    }

    /** Familles dont la presence prouve que l'evenement declare porte bien sur ce qu'il annonce (un evenement sans ancre est incoherent). */
    private static Set<Famille> ancres(CashFlowEvent e) {
        return switch (e) {
            case CAPITAL_CASH_INCREASE, INVESTMENT_GRANT_CASH_RECEIVED, CAPITAL_CASH_WITHDRAWAL, DIVIDEND_CASH_PAID -> Set.of(Famille.CAPITAUX, Famille.FINANCEMENT_AUTRE);
            case BORROWING_CASH_RECEIVED, OTHER_FINANCIAL_DEBT_CASH_RECEIVED, BORROWING_OR_LEASE_DEBT_CASH_REPAID -> Set.of(Famille.DETTES);
            case INTANGIBLE_ASSET_ACQUISITION_CASH_PAID -> Set.of(Famille.INCORPORELLE, Famille.DETTE_INVESTISSEMENT);
            case TANGIBLE_ASSET_ACQUISITION_CASH_PAID -> Set.of(Famille.CORPORELLE, Famille.DETTE_INVESTISSEMENT);
            case FINANCIAL_ASSET_ACQUISITION_CASH_PAID -> Set.of(Famille.FINANCIERE, Famille.DETTE_INVESTISSEMENT);
            case TANGIBLE_INTANGIBLE_ASSET_SALE_CASH_RECEIVED, FINANCIAL_ASSET_SALE_CASH_RECEIVED -> Set.of(Famille.CREANCE_CESSION, Famille.PRODUIT_CESSION);
            case NO_TFT_FLOW -> Set.of();
        };
    }

    // ------------------------------------------------------------------------------------------------ roles des comptes

    /** Role de chaque compte present : tresorerie et familles, par les definitions du Bilan (regles verifiees) et le classifieur ; aucun prefixe n'est teste ici. */
    private Map<UUID, Role> roles(List<LigneEcriture> lignes, FinancialStatementDefinition defActif, FinancialStatementDefinition defPassif) {
        Map<UUID, AccountInput> debiteurs = new LinkedHashMap<>();
        Map<UUID, AccountInput> crediteurs = new LinkedHashMap<>();
        for (LigneEcriture l : lignes) {
            var c = l.getCompte();
            debiteurs.putIfAbsent(c.getId(), new AccountInput(c.getId(), c.getNumero(), c.getIntitule(), BigDecimal.ONE));
            crediteurs.putIfAbsent(c.getId(), new AccountInput(c.getId(), c.getNumero(), c.getIntitule(), BigDecimal.ONE.negate()));
        }
        Map<UUID, String> ligneActif = new HashMap<>();
        Map<UUID, MappingRole> roleActif = new HashMap<>();
        resolver.resoudre(defActif, List.copyOf(debiteurs.values())).mapped().forEach(m -> {
            ligneActif.put(m.account().id(), m.lineCode());
            roleActif.put(m.account().id(), m.role());
        });
        Map<UUID, String> lignePassif = new HashMap<>();
        resolver.resoudre(defPassif, List.copyOf(crediteurs.values())).mapped().forEach(m -> lignePassif.put(m.account().id(), m.lineCode()));

        Map<UUID, Role> roles = new HashMap<>();
        for (AccountInput c : debiteurs.values()) {
            String la = ligneActif.get(c.id());
            String lp = lignePassif.get(c.id());
            boolean tresorerie = (la != null && roleActif.get(c.id()) == MappingRole.GROSS && Set.of("BQ", "BR", "BS").contains(la)) || (lp != null && Set.of("DQ", "DR").contains(lp));
            Set<Famille> familles = new LinkedHashSet<>();
            if (la != null && INCORPORELLES.contains(la)) {
                familles.add(Famille.INCORPORELLE);
            }
            if (la != null && CORPORELLES.contains(la)) {
                familles.add(Famille.CORPORELLE);
            }
            if (la != null && FINANCIERES.contains(la)) {
                familles.add(Famille.FINANCIERE);
            }
            if (lp != null && CAPITAUX.contains(lp)) {
                familles.add(Famille.CAPITAUX);
            }
            if (lp != null && DETTES_FINANCIERES.contains(lp)) {
                familles.add(Famille.DETTES);
            }
            Classification cl = classifier.classer(c.number(), roleActif.getOrDefault(c.id(), MappingRole.GROSS));
            if (cl.classe() == Classe.EXCLUDED_INVESTING) {
                familles.add(cl.dette() ? Famille.DETTE_INVESTISSEMENT : Famille.CREANCE_CESSION);
            } else if (cl.classe() == Classe.EXCLUDED_FINANCING) {
                familles.add(Famille.FINANCEMENT_AUTRE);
            }
            TermeCafg terme = classifier.termeCafg(c.number());
            if (terme == TermeCafg.PRODUIT_CESSION || terme == TermeCafg.VNC_CESSION || terme == TermeCafg.CESSION_HAO) {
                familles.add(Famille.PRODUIT_CESSION);
            }
            roles.put(c.id(), new Role(tresorerie, familles));
        }
        return roles;
    }

    private Map<UUID, List<Lineage>> lineage(List<UUID> ecritureIds) {
        if (ecritureIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<Lineage>> resultat = new HashMap<>();
        for (AccountingOperationProposal p : proposalRepository.findByEcritureIdIn(ecritureIds)) {
            resultat.computeIfAbsent(p.getEcriture().getId(), k -> new ArrayList<>()).add(new Lineage(p.getType().name(), p.getSourceType(), p.getSourceId(), p.getSourceEvent()));
        }
        return resultat;
    }

    static String listeLignes(Map<String, List<String>> nonQualifiees) {
        return nonQualifiees.keySet().stream().collect(Collectors.joining(", "));
    }
}
