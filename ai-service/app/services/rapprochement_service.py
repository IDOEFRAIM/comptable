"""Rapprochement bancaire semantique (endpoint historique /api/v1/rapprochement/semantique).

Depuis l'introduction du Universal Matching Engine (matching_engine.py), ce module n'implemente
plus sa propre logique de scoring/prompt : il CONVERTIT chaque ligne de releve + ses candidats
compatibles vers le contrat generique (MatchingRequest), delegue a `matching_engine.analyze`, puis
reconvertit la reponse vers le contrat historique (AppariementSemantique). Objectif (cf. mission
Universal Matching Engine, section 22) : un seul moteur de rapprochement entretenu, pas deux qui
divergent silencieusement avec le temps.

Le comportement EXTERNE de `proposer_appariements` reste inchange a l'identique : meme seuil de
confiance (rapprochement_seuil_confiance), meme controle de direction debit/credit, meme regle
"une ecriture et une ligne ne sont utilisees qu'une fois, priorite a la confiance la plus haute en
cas de conflit". Le detail d'implementation change : au lieu d'UN appel LLM batch sur toutes les
lignes, chaque ligne declenche son propre appel au moteur generique (qui peut lui-meme
court-circuiter l'appel LLM si le cas est deterministe evident, cf. matching_engine). Compromis
assume : plus d'appels reseau potentiels pour un gros lot de lignes non rapprochees, en echange
d'un scoring plus riche (tiers/reference/statut, pas seulement le nom) et d'un seul moteur a
maintenir. Voir ai-service/README.md pour le detail.
"""

from decimal import Decimal
from uuid import UUID

from app.core.config import get_settings
from app.schemas.matching import MatchDecision, MatchingCandidate, MatchingContext, MatchingRequest, MatchingSource, SourceType
from app.schemas.rapprochement import AppariementSemantique, LigneEcritureCandidate, LigneReleve
from app.services.matching_engine import analyze


def _direction_correspond(montant_releve: Decimal, candidat: LigneEcritureCandidate) -> bool:
    """Miroir de MatchingSemantiqueService.directionCorrespond (Java, avant migration) : positif
    (argent recu) doit correspondre a un debit du compte de tresorerie, negatif a un credit. Ce
    controle est specifique au domaine banque/ecriture (semantique du signe = sens du mouvement
    de tresorerie) : il reste ici plutot que dans le moteur generique, qui ne doit pas connaitre
    cette convention propre a la comptabilite en partie double."""
    montant_attendu = candidat.debit if montant_releve > 0 else candidat.credit
    return montant_attendu > 0


def _ligne_vers_source(ligne: LigneReleve) -> MatchingSource:
    return MatchingSource(
        type=SourceType.BANK_TRANSACTION,
        id=str(ligne.id),
        date=ligne.date_operation,
        amount=ligne.montant,
        currency="XOF",
        label=ligne.libelle,
    )


def _candidat_vers_candidate(candidat: LigneEcritureCandidate) -> MatchingCandidate:
    montant_signe = candidat.debit if candidat.debit > 0 else -candidat.credit
    return MatchingCandidate(
        type=SourceType.ACCOUNTING_ENTRY,
        id=str(candidat.id),
        date=candidat.date_ecriture,
        amount=montant_signe,
        currency="XOF",
        label=candidat.libelle,
        counterparty=candidat.tiers,
    )


def proposer_appariements(
    lignes: list[LigneReleve], candidats: list[LigneEcritureCandidate]
) -> list[AppariementSemantique]:
    if not lignes or not candidats:
        return []

    # Phase 1 : une proposition (au plus) par ligne, via le moteur generique.
    propositions: list[tuple[UUID, UUID, float, list[str]]] = []
    for ligne in lignes:
        candidats_compatibles = [c for c in candidats if _direction_correspond(ligne.montant, c)]
        if not candidats_compatibles:
            continue

        reponse = analyze(
            MatchingRequest(
                source=_ligne_vers_source(ligne),
                candidates=[_candidat_vers_candidate(c) for c in candidats_compatibles],
                context=MatchingContext(),
            )
        )
        if reponse.decision != MatchDecision.MATCH_PROPOSED or not reponse.matches:
            continue

        meilleur = max(reponse.matches, key=lambda m: m.score)
        propositions.append((ligne.id, UUID(meilleur.candidate_id), reponse.confidence, reponse.reasons))

    # Phase 2 : meme garde-fou qu'avant migration - seuil, puis priorite a la confiance la plus
    # haute en cas de conflit (une ecriture/ligne n'est utilisee qu'une fois).
    seuil = get_settings().rapprochement_seuil_confiance
    propositions = [p for p in propositions if p[2] > seuil]
    propositions.sort(key=lambda p: p[2], reverse=True)

    lignes_utilisees: set[UUID] = set()
    candidats_utilises: set[UUID] = set()
    resultat: list[AppariementSemantique] = []
    for ligne_id, candidat_id, confiance, reasons in propositions:
        if ligne_id in lignes_utilisees or candidat_id in candidats_utilises:
            continue
        resultat.append(
            AppariementSemantique(
                ligne_releve_id=ligne_id,
                ligne_ecriture_id=candidat_id,
                confiance=confiance,
                justification="; ".join(reasons) or "Correspondance proposee par le moteur de rapprochement.",
            )
        )
        lignes_utilisees.add(ligne_id)
        candidats_utilises.add(candidat_id)

    return resultat
