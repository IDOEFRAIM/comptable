from unittest.mock import patch

from app.schemas.agent import StatutAgent
from app.services import agent_orchestrator


class FakeBlock:
    def __init__(self, type_, **kwargs):
        self.type = type_
        for key, value in kwargs.items():
            setattr(self, key, value)


class FakeResponse:
    def __init__(self, content):
        self.content = content


class FakeClient:
    """Simule anthropic.Anthropic().messages.create en rejouant une sequence de reponses
    scriptees, une par appel - permet de tester la boucle complete (tool_use -> tool_result ->
    repondre_final) sans reseau."""

    def __init__(self, reponses):
        self._reponses = list(reponses)
        self.appels = 0

    class _Messages:
        def __init__(self, outer):
            self._outer = outer

        def create(self, **kwargs):
            self._outer.appels += 1
            return self._outer._reponses.pop(0)

    @property
    def messages(self):
        return FakeClient._Messages(self)


_FINAL_VALIDE = {
    "statutAgent": "SUCCES_AVEC_RESERVES",
    "typeDocument": "RECIPISSE_MOBILE_MONEY",
    "donneesExtraites": {
        "fournisseur": "Wave Money",
        "montantHt": None,
        "montantTva": None,
        "montantTtc": "15000",
        "devise": "XOF",
        "modePaiement": "WAVE",
        "referenceTransaction": "WAVE-TX-99887",
        "confiance": 0.85,
    },
    "alertesFraude": [],
    "metadataAgent": {
        "raisonnementCritique": "Doublon verifie, aucun autre montant identique sur la fenetre.",
        "necessiteValidationHumaine": False,
    },
}


def test_boucle_complete_avec_un_appel_doutil_puis_reponse_finale():
    tool_use_doublon = FakeBlock(
        "tool_use", id="tu_1", name="verifier_doublon_montant",
        input={"montant_ttc": "15000", "date_piece": "2027-01-05"},
    )
    tool_use_final = FakeBlock("tool_use", id="tu_2", name="repondre_final", input=_FINAL_VALIDE)

    fake_client = FakeClient([FakeResponse([tool_use_doublon]), FakeResponse([tool_use_final])])

    with patch("app.services.agent_orchestrator.get_client", return_value=fake_client), patch(
        "app.services.java_tools_client.verifier_doublon_montant"
    ) as mock_doublon:
        from app.schemas.agent import DoublonVerificationResponse

        mock_doublon.return_value = DoublonVerificationResponse(doublonPotentielDetecte=False, piecesSimilaires=[])

        resultat = agent_orchestrator.analyser_piece_autonome("entreprise-1", b"fakejpeg", "image/jpeg")

    assert fake_client.appels == 2
    mock_doublon.assert_called_once()
    assert resultat.statut_agent == StatutAgent.SUCCES_AVEC_RESERVES
    assert resultat.donnees_extraites.reference_transaction == "WAVE-TX-99887"
    assert resultat.metadata_agent.necessite_validation_humaine is False


def test_relance_le_modele_sil_ne_repond_pas_par_un_outil():
    texte_libre = FakeBlock("text", text="<scratchpad>je reflechis</scratchpad>")
    tool_use_final = FakeBlock("tool_use", id="tu_1", name="repondre_final", input=_FINAL_VALIDE)

    fake_client = FakeClient([FakeResponse([texte_libre]), FakeResponse([tool_use_final])])

    with patch("app.services.agent_orchestrator.get_client", return_value=fake_client):
        resultat = agent_orchestrator.analyser_piece_autonome("entreprise-1", b"fakejpeg", "image/jpeg")

    assert fake_client.appels == 2
    assert resultat.statut_agent == StatutAgent.SUCCES_AVEC_RESERVES


def test_degrade_en_echec_si_la_boucle_ne_converge_jamais():
    texte_libre = FakeBlock("text", text="<scratchpad>...</scratchpad>")
    fake_client = FakeClient([FakeResponse([texte_libre]) for _ in range(agent_orchestrator.MAX_ITERATIONS)])

    with patch("app.services.agent_orchestrator.get_client", return_value=fake_client):
        resultat = agent_orchestrator.analyser_piece_autonome("entreprise-1", b"fakejpeg", "image/jpeg")

    assert fake_client.appels == agent_orchestrator.MAX_ITERATIONS
    assert resultat.statut_agent == StatutAgent.ECHEC_SAISIE_MANUELLE
    assert resultat.metadata_agent.necessite_validation_humaine is True


def test_degrade_en_echec_si_la_reponse_finale_est_malformee():
    tool_use_final = FakeBlock("tool_use", id="tu_1", name="repondre_final", input={"statutAgent": "PAS_UNE_VALEUR_VALIDE"})
    fake_client = FakeClient([FakeResponse([tool_use_final])])

    with patch("app.services.agent_orchestrator.get_client", return_value=fake_client):
        resultat = agent_orchestrator.analyser_piece_autonome("entreprise-1", b"fakejpeg", "image/jpeg")

    assert resultat.statut_agent == StatutAgent.ECHEC_SAISIE_MANUELLE
    assert "malformee" in resultat.metadata_agent.raisonnement_critique


def test_rejette_un_type_de_fichier_non_supporte_sans_appeler_le_modele():
    fake_client = FakeClient([])

    with patch("app.services.agent_orchestrator.get_client", return_value=fake_client):
        resultat = agent_orchestrator.analyser_piece_autonome("entreprise-1", b"...", "application/zip")

    assert fake_client.appels == 0
    assert resultat.statut_agent == StatutAgent.ECHEC_SAISIE_MANUELLE


def test_outil_defaillant_ne_fait_pas_planter_la_boucle():
    tool_use_historique = FakeBlock(
        "tool_use", id="tu_1", name="consulter_historique_fournisseur", input={"fournisseur": "Boutique X"}
    )
    tool_use_final = FakeBlock("tool_use", id="tu_2", name="repondre_final", input=_FINAL_VALIDE)
    fake_client = FakeClient([FakeResponse([tool_use_historique]), FakeResponse([tool_use_final])])

    with patch("app.services.agent_orchestrator.get_client", return_value=fake_client), patch(
        "app.services.java_tools_client.consulter_historique_fournisseur", side_effect=RuntimeError("Java down")
    ):
        resultat = agent_orchestrator.analyser_piece_autonome("entreprise-1", b"fakejpeg", "image/jpeg")

    assert fake_client.appels == 2
    assert resultat.statut_agent == StatutAgent.SUCCES_AVEC_RESERVES
