"""STEP 11.4/11.6 : le catalogue CFO ne doit jamais diverger des serveurs MCP reels, et la policy
doit classer chaque tool sans exception (section 4/6/39 du plan : "utiliser les tools reellement
presents", "aucune exception" pour WRITE)."""

import asyncio
from unittest.mock import MagicMock

import pytest

from app.agent.policies.cfo_tool_policy import (
    UnknownToolError,
    approbation_requise,
    classifier_outil,
)
from app.agent.tools.catalog import CFO_TOOL_CATALOG, obtenir_descripteur
from app.mcp.finance_server import creer_finance_mcp_server
from app.mcp.server import creer_mcp_server
from app.schemas.cfo import ToolAccessClass
from app.services.accounting_client import AccountingClient
from app.services.financial_client import FinancialClient


def _noms_reels_des_serveurs_mcp() -> set[str]:
    accounting_server = creer_mcp_server(MagicMock(spec=AccountingClient))
    finance_server = creer_finance_mcp_server(MagicMock(spec=FinancialClient))
    outils = asyncio.run(accounting_server.list_tools()) + asyncio.run(finance_server.list_tools())
    return {outil.name for outil in outils}


def test_le_catalogue_ne_contient_que_des_tools_reellement_enregistres():
    noms_catalogue = {descripteur.name for descripteur in CFO_TOOL_CATALOG}
    assert noms_catalogue == _noms_reels_des_serveurs_mcp()


def test_finance_simulate_scenario_nest_jamais_dans_le_catalogue():
    # Scenario Engine n'existe pas dans ce repository (audit STEP 11) - jamais un tool fabrique.
    noms_catalogue = {descripteur.name for descripteur in CFO_TOOL_CATALOG}
    assert "finance.simulate_scenario" not in noms_catalogue


def test_obtenir_descripteur_retourne_none_pour_un_tool_absent():
    assert obtenir_descripteur("finance.simulate_scenario") is None


def test_seul_accounting_post_entry_est_classe_write():
    outils_write = {d.name for d in CFO_TOOL_CATALOG if d.access_class is ToolAccessClass.WRITE}
    assert outils_write == {"accounting.post_entry"}


def test_tous_les_tools_de_lecture_sont_classes_read():
    for descripteur in CFO_TOOL_CATALOG:
        if descripteur.name == "accounting.post_entry":
            continue
        assert descripteur.access_class is ToolAccessClass.READ


def test_approbation_requise_uniquement_pour_post_entry():
    assert approbation_requise("accounting.post_entry") is True
    assert approbation_requise("finance.get_cash_position") is False
    assert approbation_requise("accounting.get_document") is False


def test_classifier_un_tool_inconnu_leve_fail_closed():
    with pytest.raises(UnknownToolError):
        classifier_outil("finance.simulate_scenario")
    with pytest.raises(UnknownToolError):
        approbation_requise("finance.simulate_scenario")
