"""Utilitaire centralisant le pattern HITL (human-in-the-loop) interrupt/resume de LangGraph
(STEP 7.1 du plan d'implementation).

Piege decouvert en STEP 6 (voir l'avertissement qui figurait jusqu'ici directement dans
app/agent/graph/accounting.py, au-dessus de human_review_classification_node) : pour reprendre un
workflow interrompu par `interrupt(...)` SANS fournir de correction, `Command(resume={})` ne
debloque jamais rien (LangGraph 1.2.11 traite un dict comme une map {interrupt_id: valeur} destinee
aux interruptions multiples simultanees - un dict vide n'y correspond a aucune interruption) et
`Command(resume=None)` declenche un bug interne (`UnboundLocalError: resume_is_map`). La seule
maniere fiable d'acquitter sans correction est une valeur non-dict (une chaine sentinelle) ; une
correction se transmet par un dict non vide.

Ce module est desormais le SEUL endroit du code qui connaisse ce detail d'implementation. Tout
appelant qui reprend un workflow AI Accounting (tests, futur endpoint API) doit passer par
`acquitter_sans_correction()` / `reprendre_avec_correction(...)` ci-dessous, jamais construire
`Command(resume=...)` a la main."""

from typing import Any

from langgraph.types import Command, interrupt

ACQUITTEMENT_SANS_CORRECTION = "ack"
"""Sentinelle non-dict utilisee pour signifier "un humain a pris connaissance, sans correction" -
jamais un dict vide, jamais None (voir docstring de module)."""


def demander_revue_humaine(payload: dict[str, Any]) -> Any:
    """Interrompt le workflow et attend une decision humaine. Fine couche au-dessus de
    `langgraph.types.interrupt` : ne change aucun comportement, existe pour que tout point
    d'interruption du graph passe par ce module plutot que d'importer `interrupt` directement (un
    seul endroit a auditer si LangGraph change ce mecanisme)."""

    return interrupt(payload)


def acquitter_sans_correction() -> Command:
    """Reprend un workflow interrompu sans fournir de correction. Jamais
    `Command(resume={})` (ne debloque rien) ni `Command(resume=None)` (leve une exception interne
    LangGraph) - voir docstring de module."""

    return Command(resume=ACQUITTEMENT_SANS_CORRECTION)


def reprendre_avec_correction(correction: dict[str, Any]) -> Command:
    """Reprend un workflow interrompu en fournissant une correction (ex.
    {"classification": {...}}). `correction` doit etre un dict non vide - un dict vide ne
    debloquerait jamais le workflow (voir docstring de module) ; utiliser
    `acquitter_sans_correction()` pour un simple acquittement."""

    if not correction:
        raise ValueError(
            "reprendre_avec_correction() necessite un dict non vide - "
            "utiliser acquitter_sans_correction() pour acquitter sans correction."
        )
    return Command(resume=correction)


def est_acquittement_sans_correction(decision: Any) -> bool:
    """Vrai si `decision` (valeur recue par un node depuis `interrupt(...)`) est un simple
    acquittement sans correction - jamais base sur `decision is None`/`decision == {}` (les deux
    pieges que ce module existe pour eliminer)."""

    return decision == ACQUITTEMENT_SANS_CORRECTION
