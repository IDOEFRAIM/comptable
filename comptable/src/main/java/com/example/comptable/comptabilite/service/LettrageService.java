package com.example.comptable.comptabilite.service;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.domain.LigneEcriture;
import com.example.comptable.comptabilite.domain.StatutEcriture;
import com.example.comptable.comptabilite.repository.CompteComptableRepository;
import com.example.comptable.comptabilite.repository.LigneEcritureRepository;
import com.example.comptable.comptabilite.web.dto.LettrageResultDto;
import com.example.comptable.comptabilite.web.dto.LettrerLignesRequest;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.security.UtilisateurPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Lettrage : rapproche un groupe de lignes d'un meme compte tiers (ex: un reglement contre
 * la facture qu'il solde) en leur affectant un code commun. Un code de lettrage suit la
 * sequence classique A, B, ... Z, AA, AB, ... par compte.
 * <p>
 * Sprint 10 : deux ajouts par rapport au lettrage manuel d'origine.
 * <ol>
 *   <li>{@link #lettrerAutomatiquement} : meme philosophie que {@code RapprochementBancaireService}
 *   - une ligne debit et une ligne credit non lettrees de meme montant exact sur un compte sont
 *   sans ambiguite le meme mouvement, donc lettrees automatiquement ; tout le reste (montants
 *   multiples au meme prix, groupes a plus de deux lignes) reste manuel, aucune heuristique
 *   hasardeuse n'est tentee.</li>
 *   <li>{@link #lettrer} accepte desormais {@link LettrerLignesRequest#partiel()} : un reglement
 *   inferieur a la facture peut etre lettre volontairement malgre l'ecart (courant en Afrique de
 *   l'Ouest - acomptes, reglements echelonnes), l'ecart etant renvoye tel quel plutot que
 *   rejete. Le comportement par defaut (partiel=false) reste inchange : un groupe doit etre
 *   exactement equilibre.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class LettrageService {

    private final EntrepriseAccessService entrepriseAccessService;
    private final CompteComptableRepository compteComptableRepository;
    private final LigneEcritureRepository ligneEcritureRepository;

    @Transactional
    public LettrageResultDto lettrer(UUID entrepriseId, LettrerLignesRequest request, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        CompteComptable compte = chargerCompte(entrepriseId, request.compteId());
        if (!compte.isLettrable()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ce compte n'est pas lettrable");
        }
        if (request.ligneIds().size() < 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Selectionnez au moins deux lignes a lettrer");
        }

        List<LigneEcriture> lignes = ligneEcritureRepository.findAllById(request.ligneIds());
        if (lignes.size() != request.ligneIds().size()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Une ou plusieurs lignes sont introuvables");
        }
        for (LigneEcriture ligne : lignes) {
            if (!ligne.getCompte().getId().equals(request.compteId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Toutes les lignes doivent appartenir au meme compte");
            }
            if (!ligne.getEcriture().getEntreprise().getId().equals(entrepriseId)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ligne introuvable");
            }
            if (ligne.getEcriture().getStatut() != StatutEcriture.VALIDEE) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Seules les lignes d'ecritures validees peuvent etre lettrees");
            }
            if (ligne.getLettrage() != null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "La ligne du " + ligne.getEcriture().getDateEcriture() + " est deja lettree (" + ligne.getLettrage() + ")");
            }
        }

        BigDecimal totalDebit = lignes.stream().map(LigneEcriture::getDebit).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCredit = lignes.stream().map(LigneEcriture::getCredit).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal ecart = totalDebit.subtract(totalCredit).abs();
        if (ecart.signum() != 0 && !request.partiel()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Le groupe n'est pas equilibre (debit %s, credit %s, ecart %s) : le lettrage doit solder exactement les montants rapproches, "
                            + "sauf a demander explicitement un lettrage partiel (partiel=true)"
                                    .formatted(totalDebit, totalCredit, ecart));
        }

        String code = genererProchainCode(request.compteId());
        LocalDate aujourdHui = LocalDate.now();
        lignes.forEach(l -> {
            l.setLettrage(code);
            l.setDateLettrage(aujourdHui);
        });
        ligneEcritureRepository.saveAll(lignes);

        return new LettrageResultDto(code, lignes.stream().map(LigneEcriture::getId).toList(), ecart);
    }

    /**
     * Lettrage automatique : ne traite qu'un compte a la fois (coherent avec l'ecran de lettrage,
     * qui travaille deja compte par compte). Regroupe les lignes non lettrees par montant absolu ;
     * un groupe qui contient EXACTEMENT une ligne au debit et une au credit du meme montant est
     * sans ambiguite le meme mouvement rapproche. Tout groupe ambigu (plusieurs lignes au meme
     * montant d'un cote ou de l'autre) est laisse tel quel pour un lettrage manuel - pas de
     * choix arbitraire entre plusieurs candidats plausibles.
     */
    @Transactional
    public List<LettrageResultDto> lettrerAutomatiquement(UUID entrepriseId, UUID compteId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        CompteComptable compte = chargerCompte(entrepriseId, compteId);
        if (!compte.isLettrable()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ce compte n'est pas lettrable");
        }

        List<LigneEcriture> nonLettrees = ligneEcritureRepository.rechercherNonLettreesValidees(compteId);
        Map<BigDecimal, List<LigneEcriture>> parMontant = nonLettrees.stream()
                .collect(Collectors.groupingBy(l -> l.getDebit().signum() > 0 ? l.getDebit() : l.getCredit()));

        long prochainIndex = ligneEcritureRepository.countDistinctLettrageByCompteId(compteId);
        LocalDate aujourdHui = LocalDate.now();
        List<LettrageResultDto> resultats = new ArrayList<>();
        List<LigneEcriture> aSauvegarder = new ArrayList<>();

        for (List<LigneEcriture> groupe : parMontant.values()) {
            List<LigneEcriture> debits = groupe.stream().filter(l -> l.getDebit().signum() > 0).toList();
            List<LigneEcriture> credits = groupe.stream().filter(l -> l.getCredit().signum() > 0).toList();
            if (debits.size() != 1 || credits.size() != 1) {
                continue; // ambigu (ou montant sans contrepartie) : laisse pour le lettrage manuel
            }
            String code = genererCodeAlphabetique(prochainIndex++);
            LigneEcriture ligneDebit = debits.get(0);
            LigneEcriture ligneCredit = credits.get(0);
            ligneDebit.setLettrage(code);
            ligneDebit.setDateLettrage(aujourdHui);
            ligneCredit.setLettrage(code);
            ligneCredit.setDateLettrage(aujourdHui);
            aSauvegarder.add(ligneDebit);
            aSauvegarder.add(ligneCredit);
            resultats.add(new LettrageResultDto(code, List.of(ligneDebit.getId(), ligneCredit.getId()), BigDecimal.ZERO));
        }

        ligneEcritureRepository.saveAll(aSauvegarder);
        return resultats;
    }

    @Transactional
    public void delettrer(UUID entrepriseId, UUID compteId, String code, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        chargerCompte(entrepriseId, compteId);

        List<LigneEcriture> lignes = ligneEcritureRepository.findByCompteIdAndLettrage(compteId, code);
        if (lignes.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Aucune ligne lettree avec ce code sur ce compte");
        }
        lignes.forEach(l -> {
            l.setLettrage(null);
            l.setDateLettrage(null);
        });
        ligneEcritureRepository.saveAll(lignes);
    }

    /**
     * Lettrage d'un groupe deja verifie par l'appelant (allocation de paiement soldee) : meme sequence de codes que le lettrage manuel,
     * mais sans controle d'acces (appel interne, deja controle) et sans ecart toleres : un groupe desequilibre ou deja lettre est refuse.
     *
     * @return le code cree, ou {@code null} si le groupe n'est pas lettrable (ligne deja lettree, non validee, desequilibre)
     */
    @Transactional
    public String lettrerGroupeSolde(UUID entrepriseId, UUID compteId, List<UUID> ligneIds) {
        List<LigneEcriture> lignes = ligneEcritureRepository.findAllById(ligneIds);
        if (lignes.size() != ligneIds.size() || lignes.size() < 2) {
            return null;
        }
        BigDecimal debit = BigDecimal.ZERO;
        BigDecimal credit = BigDecimal.ZERO;
        for (LigneEcriture l : lignes) {
            if (!l.getCompte().getId().equals(compteId) || !l.getEcriture().getEntreprise().getId().equals(entrepriseId)
                    || l.getEcriture().getStatut() != StatutEcriture.VALIDEE || l.getLettrage() != null) {
                return null;
            }
            debit = debit.add(l.getDebit());
            credit = credit.add(l.getCredit());
        }
        if (debit.compareTo(credit) != 0) {
            return null;
        }
        String code = genererProchainCode(compteId);
        LocalDate aujourdHui = LocalDate.now();
        lignes.forEach(l -> {
            l.setLettrage(code);
            l.setDateLettrage(aujourdHui);
        });
        ligneEcritureRepository.saveAll(lignes);
        return code;
    }

    /** Defait un lettrage deja identifie (annulation d'une allocation qui l'avait produit). */
    @Transactional
    public int delettrerGroupe(UUID compteId, String code) {
        List<LigneEcriture> lignes = ligneEcritureRepository.findByCompteIdAndLettrage(compteId, code);
        lignes.forEach(l -> {
            l.setLettrage(null);
            l.setDateLettrage(null);
        });
        ligneEcritureRepository.saveAll(lignes);
        return lignes.size();
    }

    private String genererProchainCode(UUID compteId) {
        long nombreCodesExistants = ligneEcritureRepository.countDistinctLettrageByCompteId(compteId);
        return genererCodeAlphabetique(nombreCodesExistants);
    }

    /** Sequence A, B, ..., Z, AA, AB, ... (meme principe que la numerotation des colonnes d'un tableur). */
    static String genererCodeAlphabetique(long index) {
        StringBuilder code = new StringBuilder();
        long n = index + 1;
        while (n > 0) {
            n--;
            code.append((char) ('A' + (n % 26)));
            n /= 26;
        }
        return code.reverse().toString();
    }

    private CompteComptable chargerCompte(UUID entrepriseId, UUID compteId) {
        return compteComptableRepository.findById(compteId)
                .filter(c -> c.getEntreprise().getId().equals(entrepriseId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Compte introuvable"));
    }
}
