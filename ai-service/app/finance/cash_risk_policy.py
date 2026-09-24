"""Politique de risque de tresorerie (STEP 9, section 26 du plan d'implementation) : decide le
`CashRiskStatus` (HEALTHY/WATCH/AT_RISK/CRITICAL) et calcule `critical_week`/
`minimum_projected_cash` a partir du CashForecast deja calcule par Java (recu via MCP) - code
deterministe uniquement, jamais une decision du LLM ("le LLM interprete, le backend calcule",
section 2 du plan). Ne fait AUCUN calcul financier ici (pas de solde, pas de flux) : se contente de
lire/agreger des valeurs deja produites par CashForecastService (min, index d'evenement) - la meme
discipline que app/finance/health_policy.py (STEP 8)."""

from decimal import Decimal

from app.schemas.cash_risk import CashRiskStatus
from app.schemas.finance import CashForecast, LiquidityEventType


def determiner_statut(forecast: CashForecast) -> CashRiskStatus:
    """CRITICAL : au moins une semaine a un solde de cloture negatif (voir NEGATIVE_CASH cote
    Java) - le signal le plus grave, jamais attenue. AT_RISK : une tension prolongee (LIQUIDITY_GAP,
    au moins deux semaines consecutives sous le seuil). WATCH : un creux ponctuel sous le seuil
    (CASH_BELOW_THRESHOLD) sans tension prolongee. HEALTHY : aucun evenement de liquidite notable."""

    if any(semaine.closing_cash < 0 for semaine in forecast.weeks):
        return CashRiskStatus.CRITICAL
    if any(evenement.type is LiquidityEventType.LIQUIDITY_GAP for evenement in forecast.liquidity_events):
        return CashRiskStatus.AT_RISK
    if any(evenement.type is LiquidityEventType.CASH_BELOW_THRESHOLD for evenement in forecast.liquidity_events):
        return CashRiskStatus.WATCH
    return CashRiskStatus.HEALTHY


def trouver_semaine_critique(forecast: CashForecast) -> int | None:
    """Premiere semaine dont le solde de cloture est negatif ou sous le seuil minimum - None si
    aucune semaine n'est concernee (jamais une semaine devinee)."""

    for semaine in forecast.weeks:
        sous_le_seuil = forecast.minimum_cash_threshold is not None and semaine.closing_cash < forecast.minimum_cash_threshold
        if semaine.closing_cash < 0 or sous_le_seuil:
            return semaine.week_index
    return None


def calculer_minimum_projete(forecast: CashForecast) -> Decimal:
    """Solde de cloture minimum sur l'horizon - une simple lecture du minimum deja present dans
    les semaines du forecast, jamais un recalcul de solde."""

    if not forecast.weeks:
        return forecast.closing_cash
    return min(semaine.closing_cash for semaine in forecast.weeks)
