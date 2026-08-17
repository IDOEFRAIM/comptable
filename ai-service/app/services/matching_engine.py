"""Universal Matching Engine : point d'entree unique du rapprochement multi-source (transactions
bancaires/Mobile Money, ecritures, factures, documents, tiers, paiements). Pipeline obligatoire
(cf. mission section 28, "ne pas sur-agentiser") :

    input -> normalize -> filter -> score -> [court-circuit deterministe] -> LLM (1 passe) -> validate -> output

Jamais de boucle d'outils/ReAct ici (contrairement a agent_orchestrator.py, reserve aux cas
complexes) : au plus UN appel Claude par analyse, et seulement si le scoring deterministe seul
ne suffit pas a trancher. L'IA propose (MatchingResponse) ; elle ne decide, ne valide et ne
persiste jamais rien - c'est au Core Spring Boot d'appliquer ses regles metier sur la
proposition.
"""

import logging

from app.core.config import get_settings
from app.schemas.matching import (
    HistoricalPayment,
    MatchDecision,
    MatchingCandidate,
    MatchingContext,
    MatchingRequest,
    MatchingResponse,
    MatchingSource,
    MatchProposal,
    MatchType,
)
from app.services import matching_prompts
from app.services.anthropic_llm_provider import AnthropicLLMProvider
from app.services.groq_llm_provider import GroqLLMProvider
from app.services.llm_provider import LLMProvider
from app.services.matching_prefilter import filtrer_candidats
from app.services.matching_scoring import SignalScores, calculer_scores
from app.services.mock_llm_provider import MockLLMProvider

logger = logging.getLogger(__name__)


def _get_default_provider() -> LLMProvider:
    """Choisit le LLMProvider selon settings.ai_provider (cf. app/core/config.py). Le moteur
    lui-meme (analyze/_appeler_llm ci-dessous) ne connait que l'interface LLMProvider - c'est le
    seul endroit du module qui sait qu'Anthropic/Groq ou un mock existent."""
    if get_settings().ai_provider == "anthropic":
        return AnthropicLLMProvider()
    if get_settings().ai_provider == "groq":
        return GroqLLMProvider()
    return MockLLMProvider()


def analyze(request: MatchingRequest, *, llm_provider: LLMProvider | None = None) -> MatchingResponse:
    """Point d'entree du moteur. Ne leve jamais d'exception : toute panne (LLM indisponible,
    reponse malformee) degrade vers une reponse NO_MATCH/AMBIGUOUS_MATCH explicite avec
    requiresHumanReview=true plutot que de faire planter l'appelant - meme philosophie que le
    reste du microservice.

    llm_provider est optionnel : par defaut, choisi via _get_default_provider() (AI_PROVIDER=mock
    ou anthropic). Les tests injectent directement une instance (typiquement MockLLMProvider avec
    des reponses scriptees) plutot que de monkeypatcher un appel Anthropic global."""
    settings = get_settings()
    provider = llm_provider if llm_provider is not None else _get_default_provider()
    source = request.source

    candidats_filtres = filtrer_candidats(source, request.candidates, fenetre_jours=settings.matching_fenetre_jours)
    if not candidats_filtres:
        return _reponse_no_match(["no_candidates_after_prefilter"])

    scores_par_candidat = [(c, calculer_scores(source, c)) for c in candidats_filtres]
    scores_par_candidat.sort(key=lambda cs: cs[1].composite(), reverse=True)

    evident = _detecter_match_evident(scores_par_candidat, settings)
    if evident is not None:
        logger.info(
            "Universal Matching Engine : match evident detecte sans appel LLM (source=%s)", source.id
        )
        return evident

    top_candidats = scores_par_candidat[: settings.matching_max_candidates_llm]

    try:
        raw = _appeler_llm(provider, source, top_candidats, request.context)
    except Exception as exc:
        logger.warning("Universal Matching Engine : LLM indisponible pour le moment : %s", exc)
        return _reponse_indisponible(str(exc))

    return _valider_et_construire(raw, [c for c, _ in top_candidats])


def _detecter_match_evident(
    candidats_scores: list[tuple[MatchingCandidate, SignalScores]], settings
) -> MatchingResponse | None:
    """Court-circuit deterministe (section 24 de la mission) : n'appelle jamais Claude quand le
    scoring seul suffit a trancher sans ambiguite raisonnable - montant exact, reference ou tiers
    fortement identifie, et (si plusieurs candidats) un ecart net avec le second meilleur. Volon-
    tairement conservateur : au moindre doute, on laisse la main au LLM plutot que de risquer un
    faux positif deterministe."""
    if not candidats_scores:
        return None

    meilleur_candidat, meilleur_scores = candidats_scores[0]
    composite = meilleur_scores.composite()

    if composite < settings.matching_deterministic_skip_score:
        return None
    if not meilleur_scores.montant_exact:
        return None
    identification_tiers_forte = (meilleur_scores.counterparty or 0.0) >= 0.95
    if not (meilleur_scores.reference_trouvee or identification_tiers_forte):
        return None
    if len(candidats_scores) > 1:
        second_composite = candidats_scores[1][1].composite()
        if composite - second_composite < settings.matching_deterministic_skip_gap:
            return None

    reasons = ["exact_amount", "deterministic_high_confidence"]
    if meilleur_scores.reference_trouvee:
        reasons.append("reference_match")
    if identification_tiers_forte:
        reasons.append("supplier_match")
    if meilleur_scores.date == 1.0:
        reasons.append("same_day")

    return MatchingResponse(
        decision=MatchDecision.MATCH_PROPOSED,
        confidence=composite,
        match_type=MatchType.ONE_TO_ONE,
        matches=[
            MatchProposal(candidate_id=meilleur_candidat.id, candidate_type=meilleur_candidat.type, score=composite)
        ],
        reasons=reasons,
        warnings=[],
        requires_human_review=composite < settings.matching_auto_match_threshold,
    )


def _appeler_llm(
    provider: LLMProvider, source: MatchingSource,
    candidats_avec_scores: list[tuple[MatchingCandidate, SignalScores]],
    context: MatchingContext,
) -> dict:
    bloc_source = _formatter_source(source)
    bloc_candidats = "\n".join(
        _formatter_candidat(i + 1, c, s) for i, (c, s) in enumerate(candidats_avec_scores)
    )
    bloc_contexte = _formatter_contexte(context)
    user_text = matching_prompts.construire_texte_utilisateur(bloc_source, bloc_candidats, bloc_contexte)

    return provider.call_structured(
        system_prompt=matching_prompts.SYSTEM_PROMPT,
        user_text=user_text,
        tool_name="proposer_rapprochement",
        tool_description="Retourne le classement des candidats et l'explication du rapprochement propose",
        input_schema=matching_prompts.INPUT_SCHEMA,
    )


def _formatter_source(source: MatchingSource) -> str:
    parties = [
        f"Type: {source.type.value}",
        f"ID: {source.id}",
        f"Date: {source.date.isoformat()}",
        f"Montant: {source.amount} {source.currency}",
    ]
    if source.label:
        parties.append(f"Libelle: {source.label}")
    if source.reference:
        parties.append(f"Reference: {source.reference}")
    if source.counterparty:
        parties.append(f"Contrepartie: {source.counterparty}")
    return "\n".join(parties)


def _formatter_candidat(rang: int, candidat: MatchingCandidate, scores: SignalScores) -> str:
    parties = [f"{rang}.", f"  ID: {candidat.id}", f"  Type: {candidat.type.value}"]
    if candidat.date:
        parties.append(f"  Date: {candidat.date.isoformat()}")
    if candidat.amount is not None:
        parties.append(f"  Montant: {candidat.amount} {candidat.currency}")
    if candidat.label:
        parties.append(f"  Libelle: {candidat.label}")
    if candidat.counterparty:
        parties.append(f"  Contrepartie: {candidat.counterparty}")
    if candidat.reference:
        parties.append(f"  Reference: {candidat.reference}")
    if candidat.status:
        parties.append(f"  Statut: {candidat.status}")
    parties.append(
        f"  [Pre-score deterministe: {scores.composite():.2f} ; "
        f"montant_exact={scores.montant_exact} ; reference_trouvee={scores.reference_trouvee}]"
    )
    return "\n".join(parties)


def _formatter_contexte(context: MatchingContext | None) -> str:
    if context is None:
        return ""
    parties: list[str] = []
    if context.country:
        parties.append(f"Pays: {context.country}")
    if context.accounting_framework:
        parties.append(f"Referentiel comptable: {context.accounting_framework}")
    if context.history:
        parties.append("Historique de paiements avec cette contrepartie (signal probabiliste uniquement) :")
        parties.extend(_formatter_historique(h) for h in context.history[:10])
    return "\n".join(parties)


def _formatter_historique(paiement: HistoricalPayment) -> str:
    date_txt = paiement.date.isoformat() if paiement.date else "date inconnue"
    return f"  - {paiement.amount} le {date_txt} ({paiement.method or 'methode inconnue'})"


def _valider_et_construire(raw: dict, candidats: list[MatchingCandidate]) -> MatchingResponse:
    """Ne fait jamais confiance a la sortie brute du modele (section 16 de la mission) : tout
    candidateId ou candidateType absent des candidats effectivement envoyes fait rejeter
    l'INTEGRALITE du resultat (pas seulement l'entree fautive) - si le modele hallucine un
    identifiant, rien ne garantit la fiabilite du reste de sa reponse."""
    candidats_par_id = {c.id: c for c in candidats}
    matches_bruts = raw.get("matches") or []

    identifiants_invalides = []
    for m in matches_bruts:
        cid = str(m.get("candidateId", ""))
        candidat = candidats_par_id.get(cid)
        if candidat is None:
            identifiants_invalides.append(cid)
            continue
        type_annonce = str(m.get("candidateType", ""))
        if type_annonce and type_annonce != candidat.type.value:
            identifiants_invalides.append(cid)

    if identifiants_invalides:
        logger.warning(
            "Universal Matching Engine : sortie LLM invalide (identifiants/types inconnus %s), resultat rejete",
            identifiants_invalides,
        )
        return MatchingResponse(
            decision=MatchDecision.AMBIGUOUS_MATCH,
            confidence=0.0,
            match_type=MatchType.NONE,
            matches=[],
            reasons=["invalid_ai_output"],
            warnings=[
                "Le modele a reference un ou plusieurs identifiants/types absents de la requete "
                f"({', '.join(identifiants_invalides)}) ; resultat rejete par mesure de securite comptable."
            ],
            requires_human_review=True,
        )

    matches = [
        MatchProposal(
            candidate_id=candidats_par_id[str(m["candidateId"])].id,
            candidate_type=candidats_par_id[str(m["candidateId"])].type,
            score=_clamp01(_safe_float(m.get("score"), 0.0)),
        )
        for m in matches_bruts
    ]

    try:
        decision = MatchDecision(raw.get("decision"))
    except ValueError:
        decision = MatchDecision.AMBIGUOUS_MATCH

    try:
        match_type = MatchType(raw.get("matchType"))
    except ValueError:
        match_type = MatchType.NONE

    confiance = _clamp01(_safe_float(raw.get("confidence"), 0.0))
    reasons = [str(r) for r in raw.get("reasons") or []]
    warnings = [str(w) for w in raw.get("warnings") or []]

    settings = get_settings()

    # En-dessous du seuil de revue, on ne propose meme pas le match automatiquement (section 17 :
    # "< 0.70 -> pas de match automatique") plutot que de laisser remonter une proposition faible.
    if decision == MatchDecision.MATCH_PROPOSED and confiance < settings.matching_review_threshold:
        warnings.append("confidence_below_review_threshold")
        decision = MatchDecision.NO_MATCH

    if decision == MatchDecision.NO_MATCH:
        matches = []

    return MatchingResponse(
        decision=decision,
        confidence=confiance,
        match_type=match_type,
        matches=matches,
        reasons=reasons,
        warnings=warnings,
        requires_human_review=_necessite_revue_humaine(decision, confiance, settings),
    )


def _necessite_revue_humaine(decision: MatchDecision, confiance: float, settings) -> bool:
    if decision in (MatchDecision.AMBIGUOUS_MATCH, MatchDecision.PARTIAL_MATCH):
        return True
    if decision == MatchDecision.NO_MATCH:
        return False
    return confiance < settings.matching_auto_match_threshold


def _safe_float(valeur, defaut: float) -> float:
    try:
        return float(valeur)
    except (TypeError, ValueError):
        return defaut


def _clamp01(valeur: float) -> float:
    return max(0.0, min(1.0, valeur))


def _reponse_no_match(reasons: list[str]) -> MatchingResponse:
    return MatchingResponse(
        decision=MatchDecision.NO_MATCH,
        confidence=0.0,
        match_type=MatchType.NONE,
        matches=[],
        reasons=reasons,
        warnings=[],
        requires_human_review=False,
    )


def _reponse_indisponible(motif: str) -> MatchingResponse:
    return MatchingResponse(
        decision=MatchDecision.NO_MATCH,
        confidence=0.0,
        match_type=MatchType.NONE,
        matches=[],
        reasons=["llm_unavailable"],
        warnings=[f"Analyse IA indisponible pour le moment ({motif}) ; rapprochement manuel requis."],
        requires_human_review=True,
    )
