"""Filtre deterministe applique AVANT tout scoring ou appel LLM (cf. mission Universal Matching
Engine, section 11 : "Le LLM ne doit pas etre le premier filtre"). Objectif : eliminer
rapidement les candidats manifestement hors sujet (devise differente, hors fenetre de dates) sans
calcul couteux, avant le scoring fin (matching_scoring.py).
"""

import logging

from app.schemas.matching import MatchingCandidate, MatchingSource

logger = logging.getLogger(__name__)


def filtrer_candidats(
    source: MatchingSource, candidats: list[MatchingCandidate], *, fenetre_jours: int
) -> list[MatchingCandidate]:
    """Deduplique par id (garde la premiere occurrence, logue un avertissement) puis elimine :
    - les candidats d'une devise differente de la source (jamais de conversion implicite) ;
    - les candidats dont la date sort de la fenetre autorisee (fenetre large par defaut, le
      scoring fin penalise ensuite les dates eloignees plutot que de les exclure brutalement en
      dessous de ce seuil).
    Un candidat sans date connue n'est jamais elimine sur ce critere (absence d'info, pas un
    signal negatif)."""
    vus: set[str] = set()
    dedupliques: list[MatchingCandidate] = []
    for c in candidats:
        if c.id in vus:
            logger.warning("Candidat en doublon ignore lors du pre-filtrage : %s", c.id)
            continue
        vus.add(c.id)
        dedupliques.append(c)

    resultat: list[MatchingCandidate] = []
    for c in dedupliques:
        if c.currency and source.currency and c.currency != source.currency:
            continue
        if c.date is not None and abs((source.date - c.date).days) > fenetre_jours:
            continue
        resultat.append(c)
    return resultat
