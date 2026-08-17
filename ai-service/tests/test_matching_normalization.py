from app.services.matching_normalization import (
    extract_reference_tokens,
    name_similarity,
    normalize_company_name,
    normalize_text,
)


def test_normalize_text_retire_accents_ponctuation_et_espaces():
    assert normalize_text("  Café-Restaurant   Général  ") == "CAFE RESTAURANT GENERAL"


def test_normalize_text_valeur_vide():
    assert normalize_text(None) == ""
    assert normalize_text("") == ""


def test_normalize_company_name_retire_les_formes_juridiques():
    assert normalize_company_name("ABC S.A.R.L.") == normalize_company_name("ABC SARL")
    assert normalize_company_name("A.B.C SARL") == normalize_company_name("ABC SARL")


def test_name_similarity_variantes_de_raison_sociale():
    # Les 4 variantes citees dans la mission (section 10) doivent etre rapprochables.
    assert name_similarity("ABC S.A.R.L.", "ABC SARL") == 1.0
    assert name_similarity("A.B.C SARL", "ABC SARL") == 1.0
    assert name_similarity("ABC Sarl", "ABC SARL") == 1.0
    assert name_similarity("ABC SARL", "ABC SARL") == 1.0


def test_name_similarity_noms_differents_score_bas():
    assert name_similarity("ABC SARL", "XYZ SARL") < 0.5


def test_name_similarity_valeur_absente():
    assert name_similarity(None, "ABC SARL") == 0.0
    assert name_similarity("ABC SARL", None) == 0.0


def test_extract_reference_tokens_isole_une_reference_dans_un_libelle():
    tokens = extract_reference_tokens("VIR ABC SARL FAC452")
    assert "FAC452" in tokens
    assert "VIR" in tokens


def test_extract_reference_tokens_ignore_les_tokens_trop_courts():
    tokens = extract_reference_tokens("A B FAC452")
    assert "A" not in tokens
    assert "B" not in tokens
    assert "FAC452" in tokens
