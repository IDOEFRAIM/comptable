"""Politique de sante financiere (STEP 8, section 24 du plan d'implementation) : decide le
`FinancialHealthStatus` (WATCH/AT_RISK/CRITICAL) a partir des faits deja calcules par Java
(FinancialSnapshot/CashPosition/Receivables/Payables, recus via MCP) - code deterministe
uniquement, jamais une decision du LLM ("le LLM interprete, le backend calcule", section 2 du
plan). Le LLM ne fait que produire ensuite les signals/risks/positive_factors/priority_actions
(app/finance/health_assessment_service.py), toujours coherents avec ce statut, jamais en
contradiction avec lui.

Seuils deterministes et explicables (comme CollectionRiskService/CashForecastService cote Java) -
documentes honnetement comme des heuristiques ajustables, jamais un modele entraine ni un score
0-100 arbitraire (section 24 du plan : "ne pas demander au LLM un score arbitraire")."""

from decimal import Decimal

from app.schemas.financial_health import FinancialHealthStatus

SEUIL_RATIO_CREANCES_SUR_CASH_AT_RISK = Decimal(2)
"""Si les creances clients depassent 2x la tresorerie disponible, l'entreprise depend fortement du
recouvrement pour honorer ses propres engagements - signal AT_RISK (jamais CRITICAL a lui seul)."""

SEUIL_RATIO_CASH_SUR_DETTES_AT_RISK = Decimal(1)
"""Si la tresorerie ne couvre pas les dettes fournisseurs en instance, signal AT_RISK."""


def determiner_statut(*, profit: Decimal, cash: Decimal, receivables: Decimal, payables: Decimal) -> FinancialHealthStatus:
    """CRITICAL : perte operationnelle (profit < 0) ou tresorerie nulle/negative - les deux signaux
    les plus graves, jamais attenues par un profit ou un cash par ailleurs correct sur d'autres
    axes. AT_RISK : cash insuffisant pour couvrir les dettes fournisseurs en instance, ou creances
    largement superieures a la tresorerie (dependance forte au recouvrement). WATCH : par defaut -
    aucun signal fort, mais pas necessairement une situation ideale (voir section 23 du plan : un
    profit et un cash positifs peuvent tout de meme donner WATCH si les creances pesent lourd)."""

    if profit < 0 or cash <= 0:
        return FinancialHealthStatus.CRITICAL

    if payables > 0 and cash / payables < SEUIL_RATIO_CASH_SUR_DETTES_AT_RISK:
        return FinancialHealthStatus.AT_RISK
    if cash > 0 and receivables / cash > SEUIL_RATIO_CREANCES_SUR_CASH_AT_RISK:
        return FinancialHealthStatus.AT_RISK

    return FinancialHealthStatus.WATCH
