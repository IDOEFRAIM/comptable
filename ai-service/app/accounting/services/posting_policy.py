"""Politique de posting (STEP 7.7 du plan d'implementation).

Decide si une proposition comptable deja VALIDEE par Java peut etre postee sans revue humaine
supplementaire, ou doit d'abord passer par human_posting_approval (STEP 7.8) - distinct de la
revue de CLASSIFICATION (human_review_classification, plus haut dans le graph). Code deterministe
uniquement : jamais une decision du LLM, jamais encodee dans un prompt (priorite explicite du
plan - "aucune logique fiscale/de posting dans le LLM").

Regle (section 7.7 du plan, verbatim) : confiance de classification elevee + validation Java
valide + aucun avertissement critique + politique d'approbation satisfaite => posting autorise.
Ici, "aucun avertissement critique" est traite au sens le plus prudent : Java ne remonte un
avertissement que sur un compte/montant reel, jamais un bruit cosmetique - donc TOUT avertissement
declenche une revue humaine, jamais une liste de severites inventee cote Python."""

from app.schemas.accounting import AccountingClassification, ValidationResult

SEUIL_CONFIANCE_POSTING_AUTOMATIQUE = 0.85
"""Politique agentique (pas une regle comptable) : au-dessus de ce seuil de confiance de
CLASSIFICATION, et si Java valide sans reserve, le posting peut avoir lieu sans revue humaine
supplementaire. Deliberement plus eleve que SEUIL_CONFIANCE_CLASSIFICATION (0.70, dans
accounting.py, qui ne decide que si l'on revoit la classification) : poster reellement une
ecriture est une barre plus haute que proposer une classification."""


def approbation_humaine_requise_pour_poster(
    classification: AccountingClassification | None, validation: ValidationResult
) -> bool:
    """Vrai si une revue humaine (human_posting_approval) est necessaire avant de poster.

    Une proposition invalide (`validation.valid is False`) n'atteint normalement jamais cette
    fonction (le graph route direct vers finalize sans jamais poster - voir _router_apres_validation
    dans accounting.py) ; si elle l'atteignait quand meme, cette fonction refuse prudemment le
    posting automatique plutot que de supposer un appelant correct."""

    if not validation.valid:
        return True
    if validation.requires_review:
        return True
    if validation.warnings:
        return True
    if classification is None:
        return True
    return classification.confidence < SEUIL_CONFIANCE_POSTING_AUTOMATIQUE
