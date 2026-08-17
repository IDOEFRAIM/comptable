import base64

from app.schemas.fraude import AlerteFraude, NiveauRisqueFraude, TypeDocument
from app.services.groq_client import call_structured

# Prompt enrichi (Phase 3 du raisonnement en 4 phases : "Tribunal interne" / analyse de fraude
# visuelle) avec une taxonomie de codes plus fine que le seul FORMAT_VISUEL_SUSPECT d'origine,
# et un heuristique specifique aux captures d'ecran Mobile Money (le mode de falsification le
# plus courant et le plus facile en Afrique de l'Ouest, contrairement a un montage graphique qui
# demande plus de competence).
SYSTEM_PROMPT = """Tu es un expert en detection de faux documents pour un cabinet d'expertise-comptable
d'Afrique de l'Ouest (zone UEMOA/CEMAC). Examine attentivement l'image fournie (facture ou recu,
souvent un recu de paiement Mobile Money : Wave, Orange Money, MTN Money, Moov Money) a la
recherche de signes concrets de falsification ou de montage. Pour chaque anomalie que tu
retiens, utilise EXACTEMENT un de ces codes :

- POLICE_NON_UNIFORME : un montant, une date ou un texte semble avoir ete ajoute ou modifie avec
  une police differente du reste du document (rupture de police, alignement legerement decale la
  ou le montant apparait, contraste ou anti-aliasing different du reste du texte).
- LOGO_ALTERE : logo de l'operateur (Wave, Orange Money, MTN Money, Moov Money) deforme, pixelise
  de maniere suspecte (pas juste une photo floue globale), ou positionne de facon inhabituelle
  par rapport aux formats officiels de l'operateur.
- REFERENCE_ABSENTE : sur un reçu Mobile Money, aucun identifiant de transaction n'est visible
  (ID Wave, code de confirmation OM, reference MTN MoMo...), OU le document ressemble a une
  simple capture d'ecran de l'application (barre de statut du telephone visible, boutons
  d'interface, absence des elements qu'un reçu genere par l'application affiche normalement) :
  une capture d'ecran brute est le moyen le plus simple de falsifier un paiement Mobile Money en
  Afrique de l'Ouest, car son contenu peut etre modifie dans n'importe quel editeur d'image avant
  la capture. Traite ce cas comme un signal fort, pas une simple case administrative manquante.
- FORMAT_VISUEL_SUSPECT : toute autre incoherence visuelle concrete ne correspondant a aucun des
  trois codes ci-dessus (ex: montant ecrit en chiffres incoherent avec le montant en toutes
  lettres, alignement/espacement/fond incoherents avec un document reellement genere par
  l'application ou la caisse enregistreuse).

Ne signale JAMAIS une anomalie sur la seule base d'une image de mauvaise qualite (floue, mal
cadree, mal eclairee, froissee) : cela releve de la lisibilite d'une photo prise au telephone en
conditions reelles sur le terrain, pas de la falsification. Ne retiens que des signes visuels
concrets de montage, d'incoherence typographique/graphique, ou l'absence de reference sur un
reçu Mobile Money.
"""

INPUT_SCHEMA = {
    "type": "object",
    "properties": {
        "anomalies": {
            "type": "array",
            "description": "Liste des anomalies visuelles concretes detectees ; tableau vide si aucune",
            "items": {
                "type": "object",
                "properties": {
                    "code": {
                        "type": "string",
                        "enum": [
                            "POLICE_NON_UNIFORME",
                            "LOGO_ALTERE",
                            "REFERENCE_ABSENTE",
                            "FORMAT_VISUEL_SUSPECT",
                        ],
                    },
                    "motif": {
                        "type": "string",
                        "description": "Phrase courte expliquant precisement ce qui est observe",
                    },
                    "confiance": {"type": "number", "description": "Certitude sur ce constat, de 0.0 a 1.0"},
                },
                "required": ["code", "motif", "confiance"],
            },
        },
        "identifiant_fournisseur_percu": {
            "type": ["string", "null"],
            "description": "Nom du commerce/operateur ou reference de transaction lisible distinctement, null si illisible ou absent",
        },
    },
    "required": ["anomalies"],
}

# Miroir de AnalyseFraudeService.CONTENT_TYPES_ANALYSE_VISUELLE : le PDF (generalement une
# facture texte structuree) ne se prete pas a un controle typographique/graphique.
_CONTENT_TYPES_ANALYSE_VISUELLE = {"image/jpeg", "image/png"}

# Miroir de AnalyseFraudeService.TYPES_FACTURATION.
_TYPES_FACTURATION = {TypeDocument.FACTURE_ACHAT, TypeDocument.RECU}

_ACTIONS_RECOMMANDEES = {
    "POLICE_NON_UNIFORME": "Examinez l'original : demandez une nouvelle photo ou le reçu papier si le doute persiste.",
    "LOGO_ALTERE": "Examinez l'original : demandez une nouvelle photo ou le reçu papier si le doute persiste.",
    "REFERENCE_ABSENTE": "Demandez une capture incluant la reference de transaction, ou verifiez le paiement directement dans l'application Mobile Money du cabinet.",
    "FORMAT_VISUEL_SUSPECT": "Examinez l'original : demandez une nouvelle photo ou le reçu papier si le doute persiste.",
}


def analyser_visuellement(
    contenu: bytes, content_type: str, type_document: TypeDocument | None = None
) -> list[AlerteFraude]:
    """Miroir de AnalyseFraudeService.analyserVisuellement, enrichi d'une taxonomie de codes plus
    fine (cf. SYSTEM_PROMPT). N'analyse que les images (JPEG/PNG) ; ne leve jamais d'exception,
    une panne du modele degrade silencieusement vers aucune alerte visuelle (les controles
    deterministes cote Java restent operants)."""
    if content_type not in _CONTENT_TYPES_ANALYSE_VISUELLE:
        return []

    image_b64 = base64.b64encode(contenu).decode()
    try:
        raw = call_structured(
            system_prompt=SYSTEM_PROMPT,
            user_text="Analyse cette piece.",
            tool_name="analyser_piece",
            tool_description="Retourne les anomalies visuelles detectees sur la piece",
            input_schema=INPUT_SCHEMA,
            image_b64=image_b64,
            image_media_type=content_type,
        )
    except Exception:
        return []

    alertes: list[AlerteFraude] = []

    for anomalie in raw.get("anomalies", []):
        try:
            confiance = float(anomalie.get("confiance", 0.0))
            code = str(anomalie["code"])
        except (KeyError, TypeError, ValueError):
            continue
        if confiance <= 0.5 or code not in _ACTIONS_RECOMMANDEES:
            continue
        gravite = NiveauRisqueFraude.CRITIQUE if confiance >= 0.85 else NiveauRisqueFraude.MOYEN
        motif = anomalie.get("motif") or "Incoherence visuelle detectee par l'analyse IA"
        alertes.append(
            AlerteFraude(
                code=code,
                motif=motif,
                gravite=gravite,
                action_recommandee=_ACTIONS_RECOMMANDEES[code],
            )
        )

    if type_document in _TYPES_FACTURATION and not raw.get("identifiant_fournisseur_percu"):
        alertes.append(
            AlerteFraude(
                code="FOURNISSEUR_NON_IDENTIFIABLE",
                motif="Aucun nom de commerce, d'operateur ou de reference lisible sur la piece",
                gravite=NiveauRisqueFraude.MOYEN,
                action_recommandee="Identifiez manuellement le fournisseur ou l'opérateur avant de comptabiliser.",
            )
        )

    return alertes
