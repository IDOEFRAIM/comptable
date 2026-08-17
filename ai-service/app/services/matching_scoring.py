"""Scoring heuristique multi-signaux (cf. mission Universal Matching Engine, section 9 : "Ne pas
utiliser uniquement le montant"). Chaque signal est calcule independamment (0.0 a 1.0, ou None si
non applicable faute de donnee) puis combine en un score composite pondere. Ce score sert a (a)
trier/reduire les candidats avant l'appel LLM (matching_engine.py), et (b) detecter les cas
"evidents" ou l'appel LLM peut etre evite (section 24).

Aucun appel reseau/modele ici : uniquement de l'arithmetique et la normalisation de
matching_normalization.py.
"""

from dataclasses import dataclass
from decimal import Decimal

from app.schemas.matching import HistoricalPayment, MatchingCandidate, MatchingSource
from app.services.matching_normalization import extract_reference_tokens, name_similarity

# Poids relatifs des signaux dans le score composite. Renormalises sur les seuls signaux
# effectivement disponibles pour un couple source/candidat donne (cf. _composite).
_POIDS = {
    "amount": 0.35,
    "date": 0.20,
    "counterparty": 0.25,
    "reference": 0.15,
    "status": 0.05,
}

# Tolerances de montant : au-dela de exact, un ecart relatif faible est coherent avec des frais
# bancaires/Mobile Money (jamais invente comme certitude, juste un score reduit qui laisse le
# LLM/le comptable trancher).
_TOLERANCE_FRAIS = Decimal("0.02")
_TOLERANCE_PARTIEL = Decimal("0.10")


@dataclass
class SignalScores:
    amount: float | None = None
    date: float | None = None
    counterparty: float | None = None
    reference: float | None = None
    status: float | None = None
    reference_trouvee: bool = False
    montant_exact: bool = False

    def composite(self) -> float:
        signaux = {
            "amount": self.amount,
            "date": self.date,
            "counterparty": self.counterparty,
            "reference": self.reference,
            "status": self.status,
        }
        disponibles = {k: v for k, v in signaux.items() if v is not None}
        if not disponibles:
            return 0.0
        poids_total = sum(_POIDS[k] for k in disponibles)
        return sum(_POIDS[k] * v for k, v in disponibles.items()) / poids_total


def score_amount(source: MatchingSource, candidat: MatchingCandidate) -> tuple[float | None, bool]:
    """Retourne (score, montant_exact). Compare les valeurs absolues : la semantique du signe
    (sens du mouvement) est geree en amont par l'appelant si besoin (specifique au domaine, pas
    au moteur generique - cf. rapprochement_service.py pour le cas banque/ecriture)."""
    if candidat.amount is None:
        return None, False
    src, cand = abs(source.amount), abs(candidat.amount)
    if cand == 0:
        return None, False
    if src == cand:
        return 1.0, True
    ecart_relatif = abs(src - cand) / cand
    if ecart_relatif <= _TOLERANCE_FRAIS:
        return 0.85, False
    if ecart_relatif <= _TOLERANCE_PARTIEL:
        return 0.55, False
    if src < cand:
        # Source plus petite que le candidat : paiement partiel plausible, jamais l'inverse.
        return 0.30, False
    return 0.0, False


def score_date(source: MatchingSource, candidat: MatchingCandidate) -> float | None:
    if candidat.date is None:
        return None
    delta = abs((source.date - candidat.date).days)
    if delta == 0:
        return 1.0
    if delta <= 1:
        return 0.9
    if delta <= 3:
        return 0.7
    if delta <= 7:
        return 0.5
    if delta <= 30:
        return 0.2
    return 0.0


def score_counterparty(source: MatchingSource, candidat: MatchingCandidate) -> float | None:
    if candidat.counterparty and source.counterparty:
        return name_similarity(source.counterparty, candidat.counterparty)
    if candidat.counterparty and source.label:
        # Le nom du tiers n'est pas toujours dans un champ dedie cote source (ex: libelle
        # bancaire brut "VIR ABC SARL FAC452") : on tente la similarite contre le libelle complet,
        # avec un plafond pour ne pas sur-noter un match partiel de sous-chaine.
        return min(0.8, name_similarity(source.label, candidat.counterparty))
    return None


def score_reference(source: MatchingSource, candidat: MatchingCandidate) -> tuple[float | None, bool]:
    tokens_source = extract_reference_tokens(source.label) | extract_reference_tokens(source.reference)
    tokens_candidat = extract_reference_tokens(candidat.reference) | extract_reference_tokens(candidat.id)
    if not tokens_source or not tokens_candidat:
        return None, False
    if tokens_source & tokens_candidat:
        return 1.0, True
    return 0.0, False


# Statuts consideres "deja solde" quel que soit le module d'origine (facture, ecriture lettree...).
_STATUTS_SOLDES = {"PAID", "PAYEE", "SOLDE", "RECONCILED", "RAPPROCHE", "LETTREE", "CLOSED"}
_STATUTS_OUVERTS = {"UNPAID", "IMPAYEE", "PENDING", "EN_ATTENTE", "OPEN", "PARTIAL", "PARTIEL"}


def score_status(candidat: MatchingCandidate) -> float | None:
    """Une facture UNPAID est un bien meilleur candidat qu'une facture deja PAID (section 9) -
    signal d'appoint, jamais une decision a lui seul (poids volontairement faible)."""
    if not candidat.status:
        return None
    statut = candidat.status.strip().upper()
    if statut in _STATUTS_SOLDES:
        return 0.1
    if statut in _STATUTS_OUVERTS:
        return 1.0
    return 0.5


def score_history(candidat: MatchingCandidate, historique: list[HistoricalPayment]) -> float | None:
    """Signal d'appoint optionnel : le montant du candidat est-il dans la plage des paiements
    passes avec ce type de mouvement ? Purement indicatif (jamais mentionne comme une certitude
    au LLM, cf. matching_prompts.SYSTEM_PROMPT) - n'entre pas dans le score composite standard
    (poids non defini dans _POIDS), reserve a l'annotation du prompt et a l'explication."""
    if not historique or candidat.amount is None:
        return None
    montants = [abs(h.amount) for h in historique]
    if not montants:
        return None
    minimum, maximum = min(montants), max(montants)
    cible = abs(candidat.amount)
    if minimum <= cible <= maximum:
        return 1.0
    marge = (maximum - minimum) * Decimal("0.2") if maximum > minimum else maximum * Decimal("0.2")
    if minimum - marge <= cible <= maximum + marge:
        return 0.6
    return 0.2


def calculer_scores(source: MatchingSource, candidat: MatchingCandidate) -> SignalScores:
    montant_score, montant_exact = score_amount(source, candidat)
    reference_score, reference_trouvee = score_reference(source, candidat)
    return SignalScores(
        amount=montant_score,
        date=score_date(source, candidat),
        counterparty=score_counterparty(source, candidat),
        reference=reference_score,
        status=score_status(candidat),
        reference_trouvee=reference_trouvee,
        montant_exact=montant_exact,
    )
