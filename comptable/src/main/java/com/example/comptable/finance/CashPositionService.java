package com.example.comptable.finance;

import com.example.comptable.comptabilite.domain.CompteComptable;
import com.example.comptable.comptabilite.repository.CompteComptableRepository;
import com.example.comptable.comptabilite.repository.LigneEcritureRepository;
import com.example.comptable.entreprise.repository.EntrepriseRepository;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.finance.web.dto.CashPositionDto;
import com.example.comptable.finance.web.dto.PositionTresorerieDto;
import com.example.comptable.security.UtilisateurPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * "Cash Position" multicanal (mission P1, section 21-22) : consolide les comptes de tresorerie
 * (classe 5) deja saisis, sans supposer d'integration bancaire ou Mobile Money reelle (section 21
 * - "ne pas supposer qu'une integration externe existe deja"). Reutilise entierement
 * {@link LigneEcritureRepository#soldeTresorerie}/nouvelle variante par compte, jamais un second
 * calcul de solde.
 */
@Service
@RequiredArgsConstructor
public class CashPositionService {

    private final EntrepriseAccessService entrepriseAccessService;
    private final EntrepriseRepository entrepriseRepository;
    private final CompteComptableRepository compteComptableRepository;
    private final LigneEcritureRepository ligneEcritureRepository;

    @Transactional(readOnly = true)
    public CashPositionDto generer(UUID entrepriseId, LocalDate date, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        return calculer(entrepriseId, date);
    }

    /** Variante sans principal humain (STEP 8 du plan AI Accounting/AI Finance) : appel
     * serveur-a-serveur (ai-service, cle API interne) sans utilisateur interactif - verifie
     * seulement que l'entreprise existe, jamais de contournement du tenant (aucun tenant a
     * verifier ici puisqu'aucun autre tenant n'est implique dans un appel direct par
     * entrepriseId). Meme discipline que AccountingOperationEngine.validerSysteme (STEP 7). */
    @Transactional(readOnly = true)
    public CashPositionDto genererSysteme(UUID entrepriseId, LocalDate date) {
        if (!entrepriseRepository.existsById(entrepriseId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Entreprise introuvable");
        }
        return calculer(entrepriseId, date);
    }

    private CashPositionDto calculer(UUID entrepriseId, LocalDate date) {
        LocalDate dateReference = date != null ? date : LocalDate.now();

        List<PositionTresorerieDto> positions = compteComptableRepository.findByEntrepriseId(entrepriseId).stream()
                .filter(c -> c.getNumero() != null && c.getNumero().startsWith("5"))
                .map(compte -> new PositionTresorerieDto(compte.getId(), compte.getNumero(), compte.getIntitule(),
                        determinerCanal(compte), ligneEcritureRepository.soldeNetJusquau(compte.getId(), dateReference)))
                .sorted(Comparator.comparing(PositionTresorerieDto::compteNumero))
                .toList();

        BigDecimal total = positions.stream().map(PositionTresorerieDto::solde).reduce(BigDecimal.ZERO, BigDecimal::add);

        return new CashPositionDto(dateReference, total, positions);
    }

    /** Heuristique assumee (mission section 21) : aucune typologie de compte "Mobile Money"
     * n'existe dans le plan comptable aujourd'hui - le canal est derive du numero/intitule du
     * compte de tresorerie deja saisi par le comptable. Point d'extension pour une vraie
     * integration Mobile Money en P2 (le canal deviendrait alors un champ persiste plutot qu'une
     * heuristique de lecture). */
    private String determinerCanal(CompteComptable compte) {
        String intitule = compte.getIntitule() != null ? compte.getIntitule().toLowerCase() : "";
        if (intitule.contains("mobile money") || intitule.contains("orange money") || intitule.contains("moov money")
                || intitule.contains("wave") || intitule.contains("mobicash")) {
            return "MOBILE_MONEY";
        }
        if (compte.getNumero() != null && compte.getNumero().startsWith("57")) {
            return "CAISSE";
        }
        return "BANQUE";
    }
}
