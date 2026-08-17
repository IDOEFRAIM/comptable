import base64
from decimal import Decimal

from app.schemas.extraction import OcrResult
from app.services.groq_client import call_structured
from app.services.pdf_utils import rendre_premiere_page_en_png

# Tolerance d'arrondi locale (FCFA) pour le controle SYSCOHADA HT + TVA = TTC.
TOLERANCE_ARRONDI_FCFA = Decimal("5")

# Prompt structure en 4 phases (typage, extraction, auto-critique mathematique/temporelle,
# regles de prudence) : la phase 3 "analyse de fraude visuelle" et la phase 4 "routage final"
# vivent respectivement dans fraude_service.py et cote Spring Boot (StatutDocument/RapportFraude)
# - cet agent ne fait que typer, extraire et s'auto-corriger sur les montants/la date.
SYSTEM_PROMPT = """Tu es un agent d'extraction comptable pour des cabinets d'expertise-comptable
d'Afrique de l'Ouest (zone UEMOA/CEMAC, plan comptable SYSCOHADA). Les pieces que tu recois sont
le plus souvent des photos prises au telephone, parfois froissees, floues ou mal cadrees :
factures normalisees, reçus de paiement mobile (Wave, Orange Money, MTN MoMo, Moov Money),
tickets de caisse thermiques, factures proforma ou bons de commande.

Applique ce raisonnement en deux etapes avant de repondre (n'ecris pas ce raisonnement dans ta
reponse, utilise-le seulement pour remplir les champs correctement) :

ETAPE 1 - Typage du document. Classe la piece dans exactement une categorie (champ
type_document) :
- FACTURE_NORMALISEE : facture avec NIF/RCCM et mentions legales UEMOA/CEMAC
- RECIPISSE_MOBILE_MONEY : reçu Wave, Orange Money, MTN MoMo ou Moov Money
- TICKET_CAISSE : ticket de caisse thermique, souvent froisse ou partiellement efface
- FACTURE_PROFORMA : facture proforma ou bon de commande - CE N'EST PAS une charge definitive,
  classe-la toujours ainsi meme si les montants sont parfaitement lisibles
- INEXPLOITABLE : brouillard total, hors sujet (selfie, objet quelconque), ou image vide

ETAPE 2 - Auto-critique mathematique et temporelle, APRES avoir lu les montants et la date :
- Verifie toi-meme que montant_ht + montant_tva = montant_ttc, avec une tolerance de 5 FCFA pour
  les arrondis locaux. Si ce n'est pas le cas, ne corrige JAMAIS un montant que tu as lu
  distinctement pour le faire "coller" artificiellement : garde les valeurs telles que lues et
  baisse fortement confiance (0.2-0.4) en expliquant l'incoherence dans motif. Si montant_ht est
  superieur a montant_ttc, c'est une anomalie grave : confiance maximale 0.2.
- Verifie que la date n'est pas dans le futur par rapport a aujourd'hui, et n'est pas
  anormalement ancienne (plus de 2 ans) : dans les deux cas, baisse confiance et explique-le dans
  motif plutot que de l'ignorer silencieusement.

Regles strictes :
- N'invente jamais un montant, une date, un nom de fournisseur, un NIF/RCCM, une reference de
  transaction ou un mode de paiement que tu ne peux pas lire distinctement : renvoie null plutot
  qu'une valeur devinee.
- Si l'image est illisible, coupee, ou ne ressemble a rien de comptable (type_document =
  INEXPLOITABLE), renvoie confiance=0.0, tous les montants et la date a null, et explique
  pourquoi dans motif.
- Le champ confiance doit refleter honnetement ta certitude, pas une valeur par defaut
  optimiste : une image partiellement lisible doit donner une confiance intermediaire
  (ex: 0.4-0.7), pas 1.0.
- devise : suppose XOF (Franc CFA, zone UEMOA) sauf si une autre devise est explicitement
  imprimee sur la piece (ex: XAF en zone CEMAC).
- mode_paiement : identifie precisement l'operateur ou le moyen quand c'est un reçu Mobile
  Money (WAVE, ORANGE_MONEY, MTN_MONEY, MOOV_MONEY), sinon ESPECES, VIREMENT ou CARTE si
  lisible, null si non determinable.
"""

INPUT_SCHEMA = {
    "type": "object",
    "properties": {
        "type_document": {
            "type": "string",
            "enum": [
                "FACTURE_NORMALISEE",
                "RECIPISSE_MOBILE_MONEY",
                "TICKET_CAISSE",
                "FACTURE_PROFORMA",
                "INEXPLOITABLE",
            ],
            "description": "Categorie du document determinee en etape 1 du raisonnement",
        },
        "montant_ht": {
            "type": ["number", "null"],
            "description": "Montant hors taxes (XOF implicite), null si absent ou non deductible de l'image",
        },
        "montant_tva": {"type": ["number", "null"], "description": "Montant de la TVA, null si absent"},
        "montant_ttc": {
            "type": ["number", "null"],
            "description": "Montant toutes taxes comprises / montant total paye, null si absent",
        },
        "date_piece": {
            "type": ["string", "null"],
            "description": "Date de la piece au format ISO yyyy-MM-dd, null si illisible",
        },
        "confiance": {
            "type": "number",
            "description": "Certitude globale sur l'exactitude de cette extraction, de 0.0 (aucune) a 1.0 (totale), deja ajustee par l'auto-critique de l'etape 2",
        },
        "motif": {
            "type": "string",
            "description": "Phrase courte en francais expliquant le resultat, y compris toute incoherence detectee en etape 2",
        },
        "fournisseur": {
            "type": ["string", "null"],
            "description": "Nom du commerce, fournisseur ou operateur Mobile Money lisible sur la piece, null si illisible",
        },
        "nif_rccm": {
            "type": ["string", "null"],
            "description": "Numero d'Identification Fiscale ou RCCM du fournisseur, null si absent ou illisible",
        },
        "reference_transaction": {
            "type": ["string", "null"],
            "description": "Identifiant de transaction (ID Wave, code de confirmation OM, reference MTN MoMo...), null si absent ou illisible",
        },
        "devise": {
            "type": "string",
            "description": "Code devise a 3 lettres (XOF par defaut), lue sur la piece si differente",
        },
        "mode_paiement": {
            "type": ["string", "null"],
            "description": "WAVE, ORANGE_MONEY, MTN_MONEY, MOOV_MONEY, ESPECES, VIREMENT ou CARTE ; null si non determinable",
        },
    },
    "required": ["type_document", "confiance", "motif"],
}

# Media types transmis en data URL a Groq (format vision OpenAI-compatible). Liste conservee
# identique a l'ancienne integration Anthropic (mission P1.9bis "extraction/fraude sur Groq") -
# pas de raison connue de l'elargir ou de la restreindre en changeant de provider.
_MEDIA_TYPES_SUPPORTES = {"image/jpeg", "image/png", "image/gif", "image/webp"}


def extraire(contenu: bytes, content_type: str) -> OcrResult:
    if content_type == "application/pdf":
        try:
            contenu = rendre_premiere_page_en_png(contenu)
        except Exception as exc:
            return OcrResult.echec(
                f"Impossible de convertir le PDF en image pour l'analyse par IA ({exc}) ; "
                "saisie manuelle requise."
            )
        content_type = "image/png"

    if content_type not in _MEDIA_TYPES_SUPPORTES:
        return OcrResult.echec(f"Type de fichier non supporte pour l'extraction VLM: {content_type}")

    image_b64 = base64.b64encode(contenu).decode()
    try:
        raw = call_structured(
            system_prompt=SYSTEM_PROMPT,
            user_text="Voici la piece a analyser.",
            tool_name="extraire_piece",
            tool_description="Retourne le type de document, les montants et la date extraits de l'image",
            input_schema=INPUT_SCHEMA,
            image_b64=image_b64,
            image_media_type=content_type,
        )
    except Exception as exc:
        # Panne du modele (quota, reseau, reponse non parsable...) : jamais bloquant pour
        # l'utilisateur, on degrade vers "saisie manuelle requise" comme le ferait l'heuristique.
        return OcrResult.echec(
            f"Extraction par IA indisponible pour le moment ({exc}) ; saisie manuelle requise."
        )

    confiance = float(raw.get("confiance", 0.0))
    if confiance <= 0.0:
        return OcrResult.echec(raw.get("motif") or "Piece illisible ou non reconnue par le modele.")

    resultat = OcrResult(
        montant_ht=raw.get("montant_ht"),
        montant_tva=raw.get("montant_tva"),
        montant_ttc=raw.get("montant_ttc"),
        date_piece=raw.get("date_piece"),
        reussite=True,
        message=raw.get("motif") or "Extraction par IA ; a verifier avant validation.",
        confiance=confiance,
        fournisseur=raw.get("fournisseur"),
        devise=raw.get("devise") or "XOF",
        mode_paiement=raw.get("mode_paiement"),
        type_document=raw.get("type_document"),
        nif_rccm=raw.get("nif_rccm"),
        reference_transaction=raw.get("reference_transaction"),
    )
    return _verifier_coherence_arithmetique(resultat)


def _verifier_coherence_arithmetique(resultat: OcrResult) -> OcrResult:
    """Garde-fou deterministe (jamais delegue au seul jugement du modele, meme si le prompt lui
    demande deja de s'auto-corriger a l'etape 2) : recalcule montant_ht + montant_tva contre
    montant_ttc en Python. En cas d'ecart au-dela de la tolerance SYSCOHADA (5 FCFA), ne corrige
    jamais les montants lus, se contente d'ajuster confiance a la baisse et d'annoter message -
    la decision de router vers une revue humaine reste a Spring Boot (AnalyseFraudeService)."""
    ht, tva, ttc = resultat.montant_ht, resultat.montant_tva, resultat.montant_ttc
    if ht is None or tva is None or ttc is None:
        return resultat

    ecart = abs((ht + tva) - ttc)
    if ecart <= TOLERANCE_ARRONDI_FCFA:
        return resultat

    grave = ht > ttc
    confiance_ajustee = min(resultat.confiance, 0.2 if grave else 0.4)
    message = (
        f"{resultat.message or ''} [Auto-critique : HT+TVA ({ht + tva}) != TTC ({ttc}), "
        f"ecart de {ecart} FCFA au-dela de la tolerance d'arrondi]"
    ).strip()

    return resultat.model_copy(update={"confiance": confiance_ajustee, "message": message})
