"""Normalisation deterministe (regex/stdlib, aucun appel modele) des noms et libelles avant
matching - etape obligatoire avant tout scoring ou appel LLM (cf. mission Universal Matching
Engine, section 10 : "Creer des fonctions de normalisation deterministes avant le LLM").
"""

import re
import unicodedata
from difflib import SequenceMatcher

# Formes juridiques courantes en zone OHADA/UEMOA : ne discriminent pas l'identite du tiers,
# retirees avant comparaison ("ABC SARL" et "ABC SA" doivent rester des noms proches).
_FORMES_JURIDIQUES = {
    "SARL", "SARLU", "SA", "SAS", "SASU", "EURL", "GIE", "ETS", "EI", "SUARL", "SCOOP",
}

# Les points sont retires entierement (pas remplaces par un espace) : "S.A.R.L." doit redevenir
# "SARL" en un seul token, pas "S A R L" en quatre - sinon la forme juridique n'est plus
# reconnue par normalize_company_name. Les autres signes de ponctuation restent des separateurs
# de mots (deviennent un espace).
_POINTS = re.compile(r"\.")
_PONCTUATION = re.compile(r"[,;:\-_/\\'\"()]")
_ESPACES = re.compile(r"\s+")
_NON_ALPHANUM = re.compile(r"[^A-Z0-9 ]")


def normalize_text(valeur: str | None) -> str:
    """Casse, accents, ponctuation, espaces multiples -> une seule representation canonique.
    Ne modifie jamais la valeur d'origine transmise a l'appelant, sert uniquement au calcul de
    similarite interne a ce module."""
    if not valeur:
        return ""
    texte = unicodedata.normalize("NFKD", valeur)
    texte = "".join(c for c in texte if not unicodedata.combining(c))
    texte = texte.upper()
    texte = _POINTS.sub("", texte)
    texte = _PONCTUATION.sub(" ", texte)
    texte = _NON_ALPHANUM.sub("", texte)
    texte = _ESPACES.sub(" ", texte).strip()
    return texte


def normalize_company_name(valeur: str | None) -> str:
    """normalize_text + suppression des formes juridiques courantes, pour que
    "ABC S.A.R.L.", "A.B.C SARL" et "ABC Sarl" convergent vers la meme forme canonique."""
    texte = normalize_text(valeur)
    tokens = [t for t in texte.split(" ") if t and t not in _FORMES_JURIDIQUES]
    return " ".join(tokens)


def extract_reference_tokens(valeur: str | None, longueur_min: int = 3) -> set[str]:
    """Tokens alphanumeriques normalises (>= longueur_min caracteres), utiles pour reperer un
    numero de facture ou une reference transactionnelle noyee dans un libelle bancaire libre
    (ex: "VIR ABC SARL FAC452" contient le token "FAC452")."""
    texte = normalize_text(valeur)
    return {t for t in texte.split(" ") if len(t) >= longueur_min}


def name_similarity(a: str | None, b: str | None) -> float:
    """Similarite lexicale (0.0 a 1.0) entre deux noms de tiers normalises. Base sur
    difflib.SequenceMatcher (bibliotheque standard, aucune dependance externe ni appel reseau) :
    suffisant pour rapprocher des variantes orthographiques/abreviations courantes sans faire
    appel a un modele pour un signal aussi simple."""
    na, nb = normalize_company_name(a), normalize_company_name(b)
    if not na or not nb:
        return 0.0
    if na == nb:
        return 1.0
    return SequenceMatcher(None, na, nb).ratio()
