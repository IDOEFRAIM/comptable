"""Mock realiste de LLMProvider, pour faire fonctionner (et tester) tout le pipeline Universal
Matching Engine sans cle Anthropic (mission "finaliser le matching IA sans API Anthropic
disponible"). Deux modes d'usage, tous deux exposes par la meme classe :

1. Reponses scriptees (parametre `responses`) : pour les tests unitaires qui veulent un controle
   precis et explicite - une file d'attente de dicts (reponse brute, meme forme que
   anthropic_client.call_structured) ou d'exceptions (pour simuler une panne), consommee dans
   l'ordre, un element par appel. C'est le mode utilise par tests/test_matching_engine.py, en
   remplacement direct de l'ancien patch("...call_structured").

2. Detection par mot-cle dans le texte envoye (aucune reponse scriptee, ou file epuisee) : pour
   faire tourner ai-service tel quel (AI_PROVIDER=mock, aucun test ne pilote directement le
   provider) et obtenir un scenario precis en placant un mot-cle dans le libelle/la reference de
   la transaction source - un peu comme les numeros de carte de test de Stripe. Sans mot-cle, le
   mock propose le premier candidat trouve dans le texte avec une confiance elevee (reprend
   l'exemple "Cas 1" de la mission).

Mots-cles reconnus (a placer dans le libelle ou la reference de la MatchingSource) :
    MOCK_AMBIGUOUS      -> AMBIGUOUS_MATCH avec les 2 premiers candidats du texte
    MOCK_NO_MATCH       -> NO_MATCH, aucun candidat
    MOCK_PARTIAL        -> PARTIAL_MATCH sur le premier candidat
    MOCK_INVALID_JSON   -> leve ValueError (simule une reponse LLM non structuree)
    MOCK_HALLUCINATE    -> renvoie un candidateId absent des candidats envoyes
    MOCK_TIMEOUT        -> leve TimeoutError (simule une panne/latence du provider)
"""

import logging
import re
import time
from typing import Any

from app.services.llm_provider import LLMProvider

_MOTIF_CANDIDAT = re.compile(r"^  ID: (\S+)\n  Type: (\S+)$", re.MULTILINE)

logger = logging.getLogger(__name__)


class MockLLMProvider(LLMProvider):
    def __init__(self, responses: list[dict[str, Any] | Exception] | None = None):
        self._responses: list[dict[str, Any] | Exception] = list(responses) if responses else []
        self.calls: list[dict[str, Any]] = []

    def call_structured(
        self,
        *,
        system_prompt: str,
        user_text: str,
        tool_name: str,
        tool_description: str,
        input_schema: dict[str, Any],
    ) -> dict[str, Any]:
        self.calls.append(
            {
                "system_prompt": system_prompt,
                "user_text": user_text,
                "tool_name": tool_name,
                "tool_description": tool_description,
                "input_schema": input_schema,
            }
        )

        debut = time.perf_counter()
        try:
            resultat = self._resoudre(tool_name, user_text)
        except Exception as exc:
            self._tracer_appel(tool_name, debut, succes=False, erreur=exc)
            raise
        self._tracer_appel(tool_name, debut, succes=True)
        return resultat

    def _resoudre(self, tool_name: str, user_text: str) -> dict[str, Any]:
        if self._responses:
            prochaine = self._responses.pop(0)
            if isinstance(prochaine, Exception):
                raise prochaine
            return prochaine

        if tool_name != "proposer_rapprochement":
            # Mission P1 (Copilot) : le mock du Universal Matching Engine ne doit pas repondre a
            # la place d'un autre outil (ex: "expliquer_dossier") - reponse generique neutre,
            # jamais utilisee par un test qui verifie un contenu precis (qui scripte toujours
            # `responses`), seulement pour que AI_PROVIDER=mock reste utilisable de bout en bout.
            return {"explanation": "Reponse generee automatiquement (mode simulation) a partir des donnees fournies."}

        return self._scenario_depuis_le_texte(user_text)

    @staticmethod
    def _tracer_appel(operation: str, debut: float, *, succes: bool, erreur: Exception | None = None) -> None:
        """Meme contrat de telemetrie que anthropic_client._tracer_appel_ia (mission P1.9), pour
        que les operateurs en environnement AI_PROVIDER=mock (dev/CI/demo) voient aussi une trace
        de latence/succes - sans quoi la telemetrie IA serait invisible partout sauf en prod."""
        latence_ms = round((time.perf_counter() - debut) * 1000)
        if succes:
            logger.info("ai_call provider=mock operation=%s success=true latency_ms=%d", operation, latence_ms)
        else:
            logger.warning(
                "ai_call provider=mock operation=%s success=false latency_ms=%d error=%s",
                operation,
                latence_ms,
                type(erreur).__name__ if erreur is not None else "unknown",
            )

    def _scenario_depuis_le_texte(self, user_text: str) -> dict[str, Any]:
        candidats = _MOTIF_CANDIDAT.findall(user_text)

        if "MOCK_TIMEOUT" in user_text:
            raise TimeoutError("mock : provider LLM indisponible (scenario de test AI_PROVIDER=mock)")
        if "MOCK_INVALID_JSON" in user_text:
            raise ValueError("mock : reponse LLM non structuree (scenario de test AI_PROVIDER=mock)")

        if "MOCK_HALLUCINATE" in user_text:
            return {
                "decision": "MATCH_PROPOSED",
                "confidence": 0.97,
                "matchType": "ONE_TO_ONE",
                "matches": [
                    {"candidateId": "id-invente-par-le-mock", "candidateType": "ACCOUNTING_ENTRY", "score": 0.97}
                ],
                "reasons": ["exact_amount"],
                "warnings": [],
                "requiresHumanReview": False,
            }

        if "MOCK_AMBIGUOUS" in user_text and len(candidats) >= 2:
            return {
                "decision": "AMBIGUOUS_MATCH",
                "confidence": 0.61,
                "matchType": "NONE",
                "matches": [
                    {"candidateId": cid, "candidateType": ctype, "score": 0.63 - (0.03 * i)}
                    for i, (cid, ctype) in enumerate(candidats[:2])
                ],
                "reasons": ["multiple_plausible_candidates"],
                "warnings": ["Plusieurs candidats plausibles, aucun ne se distingue nettement."],
                "requiresHumanReview": True,
            }

        if "MOCK_NO_MATCH" in user_text:
            return {
                "decision": "NO_MATCH",
                "confidence": 0.1,
                "matchType": "NONE",
                "matches": [],
                "reasons": ["no_plausible_candidate"],
                "warnings": [],
                "requiresHumanReview": False,
            }

        if "MOCK_PARTIAL" in user_text and candidats:
            cid, ctype = candidats[0]
            return {
                "decision": "PARTIAL_MATCH",
                "confidence": 0.85,
                "matchType": "PARTIAL_PAYMENT",
                "matches": [{"candidateId": cid, "candidateType": ctype, "score": 0.85}],
                "reasons": ["partial_amount"],
                "warnings": ["Montant inferieur au candidat : solde restant a verifier."],
                "requiresHumanReview": True,
            }

        if candidats:
            cid, ctype = candidats[0]
            return {
                "decision": "MATCH_PROPOSED",
                "confidence": 0.97,
                "matchType": "ONE_TO_ONE",
                "matches": [{"candidateId": cid, "candidateType": ctype, "score": 0.97}],
                "reasons": ["exact_amount", "same_supplier", "close_date", "matching_reference"],
                "warnings": [],
                "requiresHumanReview": False,
            }

        return {
            "decision": "NO_MATCH",
            "confidence": 0.0,
            "matchType": "NONE",
            "matches": [],
            "reasons": ["no_candidates_in_prompt"],
            "warnings": [],
            "requiresHumanReview": False,
        }
