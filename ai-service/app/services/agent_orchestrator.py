import base64
import json
import logging
from datetime import date
from decimal import Decimal
from typing import Any, NamedTuple

from pydantic import ValidationError

from app.core.config import get_settings
from app.schemas.agent import AnalyseAgentResponse, DonneesExtraitesAgent, MetadataAgent, StatutAgent
from app.schemas.extraction import TypeDocumentDetecte
from app.services import bedrock_client, java_tools_client
from app.services.anthropic_client import get_client
from app.services.bedrock_client import _FORMATS_IMAGE
from app.services.pdf_utils import preparer_image_pour_vlm, rendre_premiere_page_en_png

logger = logging.getLogger(__name__)

# Nombre maximal d'allers-retours modele<->outils avant abandon : protege contre une boucle qui
# ne convergerait jamais vers repondre_final (cout et latence bornes), jamais un crash - degrade
# vers une reponse ECHEC_SAISIE_MANUELLE explicite (cf. _reponse_echec).
MAX_ITERATIONS = 6

_MEDIA_TYPES_SUPPORTES = {"image/jpeg", "image/png", "image/gif", "image/webp"}

# System prompt de l'Agent Comptable Autonome : reprend le protocole en 4 etapes (typage,
# extraction+controle mathematique, appels d'outils strategiques, decision/routage) et les
# regles metier UEMOA/CEMAC specifiees par le cabinet. Le <scratchpad> demande a chaque etape
# sert de trace d'audit lisible dans la conversation (utile en cas de litige/relecture humaine) ;
# il n'est pas reparse programmatiquement, seul l'appel final a repondre_final fait foi pour le
# JSON retourne - metadataAgent.raisonnementCritique en est le resume machine-lisible.
SYSTEM_PROMPT = """Tu es l'Agent Comptable Autonome d'un SaaS d'expertise comptable (zone OHADA/UEMOA/CEMAC).
Ton role est d'analyser, auditer et router les pieces financieres entrantes (factures, reçus
Mobile Money Wave, Orange Money, MTN, Moov) avec la rigueur d'un commissaire aux comptes.

Tu disposes d'outils que tu DOIS appeler activement des qu'une information manque, qu'un risque
de fraude emerge, ou qu'un croisement avec la base de donnees est necessaire. N'invente jamais
une information : utilise tes outils plutot que de deviner.

# PRINCIPES DIRECTEURS DE L'AUDIT
1. Scepticisme professionnel permanent : tout document est suspect jusqu'a preuve du contraire.
2. Arithmetique stricte : recalcule systematiquement HT + TVA = TTC (tolerance +/-5 FCFA). Ne
   fais jamais confiance aux totaux affiches par le document s'ils sont incoherents.
3. Tracabilite des risques : chaque anomalie visuelle ou mathematique doit etre qualifiee (code,
   gravite CRITIQUE ou MOYEN).

# PROTOCOLE DE RAISONNEMENT (a suivre a chaque etape)
Avant chaque decision ou appel d'outil, ecris ton analyse dans une balise <scratchpad>, en
suivant ces 4 etapes :

- ETAPE 1 (TYPAGE & VALIDATION VISUELLE) : identifie le type exact du document
  (FACTURE_NORMALISEE, RECIPISSE_MOBILE_MONEY, TICKET_CAISSE, RECU_BANCAIRE, FACTURE_PROFORMA, INEXPLOITABLE).
  Repere les indices de falsification visuelle (police non uniforme, logo altere, capture
  d'ecran pure sans metadonnees d'application).
- ETAPE 2 (EXTRACTION & CONTROLE MATHEMATIQUE) : extrais montants (HT, TVA, TTC), date,
  fournisseur, NIF/RCCM et reference de transaction (TxID). Verifie l'equation arithmetique.
- ETAPE 3 (APPEL(S) D'OUTIL(S) STRATEGIQUE(S)) :
  * Reçu Mobile Money avec reference de transaction absente ou suspecte -> appelle
    verifier_doublon_montant.
  * Fournisseur identifie mais montant/recurrence inhabituel -> appelle
    consulter_historique_fournisseur.
  * Information critique manquante (NIF sur facture B2B, nature de depense floue) -> appelle
    demander_clarification_client.
- ETAPE 4 (DECISION & ROUTAGE FINAL) : une fois les outils executes et leurs retours integres,
  appelle repondre_final avec ton verdict :
  * SUCCES : donnees propres, arithmetique exacte, aucun risque de fraude.
  * SUCCES_AVEC_RESERVES : donnees exploitables mais alertes mineures (format legerement
    compresse, ecart minime justifie).
  * ECHEC_SAISIE_MANUELLE : fraude averee, ou facture proforma non comptabilisable.
  * ATTENTE_CLIENT : information bloquante manquante, une clarification a ete demandee.

# REGLES METIER SPECIFIQUES (AFRIQUE DE L'OUEST)
- Reçus Mobile Money : une simple capture d'ecran d'application (sans SMS officiel de
  l'operateur ni TxID tracable) est classee d'office REFERENCE_ABSENTE, gravite CRITIQUE,
  echec de saisie automatique. Exige une reference formelle.
- Factures proforma : si "Proforma", "Devis" ou "Facture Pro-forma" apparait, le document ne
  peut jamais etre valide comme une charge definitive. Type-le FACTURE_PROFORMA et bloque la
  comptabilisation (statutAgent=ECHEC_SAISIE_MANUELLE, alerte DOCUMENT_NON_COMPTABLE).

# SORTIE
Une fois ton analyse terminee et les retours d'outils integres, appelle l'outil repondre_final
UNE SEULE FOIS avec ton verdict complet. N'ecris aucun texte en dehors des balises <scratchpad>
et de l'appel a repondre_final.
"""

_OUTILS_INFORMATIFS = {
    "verifier_doublon_montant": {
        "name": "verifier_doublon_montant",
        "description": (
            "Recherche des pieces deja enregistrees avec le meme montant TTC a quelques jours "
            "pres, pour detecter un doublon potentiel - utile en particulier si la reference de "
            "transaction Mobile Money est absente ou suspecte."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "montant_ttc": {"type": "string", "description": "Montant TTC a verifier, tel qu'extrait"},
                "date_piece": {"type": "string", "description": "Date de la piece, format ISO yyyy-MM-dd"},
            },
            "required": ["montant_ttc", "date_piece"],
        },
    },
    "consulter_historique_fournisseur": {
        "name": "consulter_historique_fournisseur",
        "description": (
            "Recupere les statistiques (nombre de pieces, montant moyen/min/max, dernieres "
            "pieces) des transactions passees avec ce fournisseur pour ce client, pour juger si "
            "le montant ou la recurrence de cette piece est inhabituel."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "fournisseur": {
                    "type": "string",
                    "description": "Nom du fournisseur/commerce/operateur tel qu'extrait",
                },
            },
            "required": ["fournisseur"],
        },
    },
    "demander_clarification_client": {
        "name": "demander_clarification_client",
        "description": (
            "Cree une demande de clarification adressee au client, quand une information "
            "critique manque (NIF sur une facture B2B, nature de la depense floue) et empeche "
            "de statuer."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "libelle": {"type": "string", "description": "Resume court de ce qui manque"},
                "description": {"type": "string", "description": "Explication detaillee pour le client"},
            },
            "required": ["libelle"],
        },
    },
}

_REPONDRE_FINAL_SCHEMA = {
    "type": "object",
    "properties": {
        "statutAgent": {
            "type": "string",
            "enum": ["SUCCES", "SUCCES_AVEC_RESERVES", "ECHEC_SAISIE_MANUELLE", "ATTENTE_CLIENT"],
        },
        "typeDocument": {
            "type": "string",
            "enum": [
                "FACTURE_NORMALISEE",
                "RECIPISSE_MOBILE_MONEY",
                "TICKET_CAISSE",
                "RECU_BANCAIRE",
                "FACTURE_PROFORMA",
                "INEXPLOITABLE",
            ],
        },
        "donneesExtraites": {
            "type": "object",
            "properties": {
                "fournisseur": {"type": ["string", "null"]},
                "nifRccm": {"type": ["string", "null"]},
                "datePiece": {"type": ["string", "null"], "description": "format ISO yyyy-MM-dd"},
                "montantHt": {"type": ["string", "null"]},
                "montantTva": {"type": ["string", "null"]},
                "montantTtc": {"type": ["string", "null"]},
                "devise": {"type": "string", "enum": ["XOF", "XAF", "EUR", "USD"]},
                "modePaiement": {
                    "type": ["string", "null"],
                    "description": "WAVE, ORANGE_MONEY, MTN_MOMO, ESPECES, VIREMENT ou AUTRE",
                },
                "referenceTransaction": {"type": ["string", "null"]},
                "confiance": {"type": "number", "description": "0.0 a 1.0"},
            },
            "required": ["confiance"],
        },
        "alertesFraude": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "code": {
                        "type": "string",
                        "enum": [
                            "FORMAT_VISUEL_SUSPECT",
                            "LOGO_ALTERE",
                            "POLICE_NON_UNIFORME",
                            "REFERENCE_ABSENTE",
                            "ANOMALIE_MATHEMATIQUE",
                            "DOCUMENT_NON_COMPTABLE",
                        ],
                    },
                    "motif": {"type": "string"},
                    "gravite": {"type": "string", "enum": ["CRITIQUE", "MOYEN"]},
                    "actionRecommandee": {"type": "string"},
                },
                "required": ["code", "motif", "gravite", "actionRecommandee"],
            },
        },
        "metadataAgent": {
            "type": "object",
            "properties": {
                "raisonnementCritique": {
                    "type": "string",
                    "description": "Resume etape par etape de l'analyse logique de l'agent",
                },
                "necessiteValidationHumaine": {"type": "boolean"},
            },
            "required": ["raisonnementCritique", "necessiteValidationHumaine"],
        },
    },
    "required": ["statutAgent", "typeDocument", "donneesExtraites", "alertesFraude", "metadataAgent"],
}

_TOOLS = [
    *_OUTILS_INFORMATIFS.values(),
    {
        "name": "repondre_final",
        "description": (
            "Renvoie le verdict final de l'analyse, une fois le raisonnement en 4 etapes "
            "termine et les outils necessaires appeles. A appeler UNE SEULE FOIS, en dernier."
        ),
        "input_schema": _REPONDRE_FINAL_SCHEMA,
    },
]


_MESSAGE_RELANCE = "Merci d'appeler l'outil repondre_final avec ton verdict complet."
_MESSAGE_ANALYSE = "Analyse cette piece comptable en suivant le protocole en 4 etapes."


class _AppelOutil(NamedTuple):
    id: str
    nom: str
    entree: dict[str, Any]


class _SessionAnthropic:
    """Boucle agentique sur l'API Messages d'Anthropic (AI_PROVIDER autre que bedrock)."""

    def __init__(self, contenu: bytes, content_type: str) -> None:
        self._client = get_client()
        self._messages: list[dict[str, Any]] = [
            {
                "role": "user",
                "content": [
                    {
                        "type": "image",
                        "source": {"type": "base64", "media_type": content_type, "data": base64.b64encode(contenu).decode()},
                    },
                    {"type": "text", "text": _MESSAGE_ANALYSE},
                ],
            }
        ]

    def tour(self) -> list[_AppelOutil]:
        response = self._client.messages.create(
            model=get_settings().anthropic_model,
            max_tokens=2048,
            system=SYSTEM_PROMPT,
            tools=_TOOLS,
            messages=self._messages,
        )
        self._messages.append({"role": "assistant", "content": response.content})
        return [_AppelOutil(b.id, b.name, b.input) for b in response.content if b.type == "tool_use"]

    def relancer(self) -> None:
        self._messages.append({"role": "user", "content": _MESSAGE_RELANCE})

    def repondre(self, resultats: list[tuple[_AppelOutil, dict[str, Any]]]) -> None:
        self._messages.append(
            {
                "role": "user",
                "content": [
                    {
                        "type": "tool_result",
                        "tool_use_id": appel.id,
                        "content": json.dumps(resultat, default=str, ensure_ascii=False),
                    }
                    for appel, resultat in resultats
                ],
            }
        )


class _SessionBedrock:
    """Meme boucle sur Amazon Bedrock (Claude, API Converse) : blocs toolUse / toolResult, image en octets
    bruts. Selectionnee par AI_PROVIDER=bedrock ; identifiants = role IAM de l'instance (cf. bedrock_client)."""

    def __init__(self, contenu: bytes, content_type: str) -> None:
        self._messages: list[dict[str, Any]] = [
            {
                "role": "user",
                "content": [
                    {"image": {"format": _FORMATS_IMAGE[content_type], "source": {"bytes": contenu}}},
                    {"text": _MESSAGE_ANALYSE},
                ],
            }
        ]

    def tour(self) -> list[_AppelOutil]:
        message = bedrock_client.converse_avec_outils(system_prompt=SYSTEM_PROMPT, messages=self._messages, outils=_TOOLS)
        if not message.get("content"):
            # Converse refuse un message assistant vide dans l'historique
            message = {"role": "assistant", "content": [{"text": "(aucune reponse)"}]}
        self._messages.append(message)
        return [
            _AppelOutil(b["toolUse"]["toolUseId"], b["toolUse"]["name"], b["toolUse"].get("input") or {})
            for b in message["content"]
            if "toolUse" in b
        ]

    def relancer(self) -> None:
        self._messages.append({"role": "user", "content": [{"text": _MESSAGE_RELANCE}]})

    def repondre(self, resultats: list[tuple[_AppelOutil, dict[str, Any]]]) -> None:
        blocs = []
        for appel, resultat in resultats:
            bloc: dict[str, Any] = {
                "toolUseId": appel.id,
                # Converse exige un objet JSON pur (Decimal, date... passes par default=str)
                "content": [{"json": json.loads(json.dumps(resultat, default=str))}],
            }
            if "erreur" in resultat:
                bloc["status"] = "error"
            blocs.append({"toolResult": bloc})
        self._messages.append({"role": "user", "content": blocs})


def _creer_session(contenu: bytes, content_type: str) -> _SessionAnthropic | _SessionBedrock:
    if get_settings().ai_provider == "bedrock":
        return _SessionBedrock(contenu, content_type)
    return _SessionAnthropic(contenu, content_type)


def analyser_piece_autonome(entreprise_id: str, contenu: bytes, content_type: str) -> AnalyseAgentResponse:
    """Point d'entree de l'agent comptable autonome : boucle de raisonnement reelle (pas un
    simple appel structure) ou le modele decide lui-meme, tour apres tour, quels outils appeler
    avant de statuer. Ne leve jamais d'exception : toute panne (modele, outil Java, boucle qui ne
    converge pas) degrade vers une reponse ECHEC_SAISIE_MANUELLE explicite plutot que de faire
    planter l'appelant."""
    if content_type == "application/pdf":
        try:
            contenu = rendre_premiere_page_en_png(contenu)
        except Exception as exc:
            return _reponse_echec(f"Impossible de convertir le PDF en image pour l'analyse ({exc}).")
        content_type = "image/png"

    if content_type not in _MEDIA_TYPES_SUPPORTES:
        return _reponse_echec(f"Type de fichier non supporte : {content_type}.")

    contenu, content_type = preparer_image_pour_vlm(contenu)
    session = _creer_session(contenu, content_type)

    for _ in range(MAX_ITERATIONS):
        try:
            appels = session.tour()
        except Exception as exc:
            logger.warning("Agent comptable autonome indisponible pour le moment : %s", exc)
            return _reponse_echec(f"Agent IA indisponible pour le moment ({exc}) ; saisie manuelle requise.")

        if not appels:
            # Le modele a repondu en texte libre (scratchpad seul) sans appeler repondre_final :
            # on le relance plutot que de deviner un JSON depuis du texte libre.
            session.relancer()
            continue

        appel_final = next((a for a in appels if a.nom == "repondre_final"), None)
        if appel_final is not None:
            return _construire_reponse(appel_final.entree)

        session.repondre([(a, _executer_outil(entreprise_id, a.nom, a.entree)) for a in appels])

    logger.warning("Agent comptable autonome : boucle non convergee apres %s iterations", MAX_ITERATIONS)
    return _reponse_echec(
        f"L'analyse n'a pas abouti a un verdict apres {MAX_ITERATIONS} etapes de raisonnement ; "
        "saisie manuelle requise."
    )


def _executer_outil(entreprise_id: str, nom: str, entree: dict[str, Any]) -> dict[str, Any]:
    """Execute un outil informatif et retourne un dict serialisable en JSON pour le tool_result.
    Ne leve jamais d'exception : une panne d'un outil (Java indisponible, entree malformee) est
    transmise au modele comme un resultat d'erreur, pour qu'il adapte son raisonnement (ex:
    baisser sa confiance) plutot que de faire echouer toute l'analyse."""
    try:
        if nom == "verifier_doublon_montant":
            montant = Decimal(str(entree["montant_ttc"]))
            date_piece = _parser_date(entree["date_piece"])
            resultat = java_tools_client.verifier_doublon_montant(entreprise_id, montant, date_piece)
            return resultat.model_dump(mode="json", by_alias=True)

        if nom == "consulter_historique_fournisseur":
            resultat = java_tools_client.consulter_historique_fournisseur(entreprise_id, entree["fournisseur"])
            return resultat.model_dump(mode="json", by_alias=True)

        if nom == "demander_clarification_client":
            resultat = java_tools_client.demander_clarification_client(
                entreprise_id, entree["libelle"], entree.get("description")
            )
            return resultat.model_dump(mode="json", by_alias=True)

        return {"erreur": f"Outil inconnu : {nom}"}
    except Exception as exc:
        logger.warning("Outil %s indisponible pour le moment : %s", nom, exc)
        return {"erreur": f"Outil indisponible pour le moment ({exc})"}


def _parser_date(valeur: str) -> date:
    return date.fromisoformat(valeur)


def _construire_reponse(donnees_brutes: dict[str, Any]) -> AnalyseAgentResponse:
    """Ne fait jamais confiance a la structure brute renvoyee par le modele sans validation :
    une reponse malformee degrade vers un ECHEC explicite plutot que de propager une exception
    de validation Pydantic non geree jusqu'a l'appelant."""
    try:
        return AnalyseAgentResponse.model_validate(donnees_brutes)
    except ValidationError as exc:
        logger.warning("Reponse finale de l'agent comptable malformee : %s", exc)
        return _reponse_echec(f"Reponse de l'agent malformee ({exc}) ; saisie manuelle requise.")


def _reponse_echec(motif: str) -> AnalyseAgentResponse:
    return AnalyseAgentResponse(
        statut_agent=StatutAgent.ECHEC_SAISIE_MANUELLE,
        type_document=TypeDocumentDetecte.INEXPLOITABLE,
        donnees_extraites=DonneesExtraitesAgent(confiance=0.0),
        alertes_fraude=[],
        metadata_agent=MetadataAgent(raisonnement_critique=motif, necessite_validation_humaine=True),
    )
