package com.example.comptable.stock.service;

import com.example.comptable.entreprise.domain.Entreprise;
import com.example.comptable.entreprise.service.EntrepriseAccessService;
import com.example.comptable.security.UtilisateurPrincipal;
import com.example.comptable.stock.domain.Depot;
import com.example.comptable.stock.repository.DepotRepository;
import com.example.comptable.stock.web.dto.CreerDepotRequest;
import com.example.comptable.stock.web.dto.DepotDto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/** CRUD minimal pour l'abstraction {@link Depot} - prepare les transferts inter-depots (section 15
 * de la mission stock) sans construire un vrai modele multi-etablissements. */
@Service
@RequiredArgsConstructor
public class DepotService {

    private final EntrepriseAccessService entrepriseAccessService;
    private final DepotRepository depotRepository;

    @Transactional
    public DepotDto creer(UUID entrepriseId, CreerDepotRequest request, UtilisateurPrincipal principal) {
        Entreprise entreprise = entrepriseAccessService.verifierAcces(entrepriseId, principal);

        if (depotRepository.findByEntrepriseIdAndCode(entrepriseId, request.code()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Un depot avec le code " + request.code() + " existe deja");
        }

        Depot depot = new Depot();
        depot.setEntreprise(entreprise);
        depot.setCode(request.code());
        depot.setLibelle(request.libelle());
        return DepotDto.from(depotRepository.save(depot));
    }

    @Transactional(readOnly = true)
    public List<DepotDto> lister(UUID entrepriseId, UtilisateurPrincipal principal) {
        entrepriseAccessService.verifierAcces(entrepriseId, principal);
        return depotRepository.findByEntrepriseIdOrderByLibelleAsc(entrepriseId).stream()
                .map(DepotDto::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public Depot chargerEntite(UUID entrepriseId, UUID depotId) {
        return depotRepository.findById(depotId)
                .filter(d -> d.getEntreprise().getId().equals(entrepriseId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Depot introuvable"));
    }
}
