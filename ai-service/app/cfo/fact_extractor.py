"""FinancialFactExtractor (STEP 11.8 du plan d'implementation) : transforme les reponses DEJA
structurees des tools MCP en FinancialFact - jamais le LLM ne construit un fait financier (section
12 du plan : "ne pas laisser le LLM construire les facts financiers"). Chaque fact reste une
projection 1:1 d'un champ deja renvoye par Java (meme discipline que app/schemas/finance.py depuis
STEP 8) - aucune addition/moyenne/ratio recalculee ici au-dela de ce qui est deja expose par un
autre service deterministe deja existant (ex: calculer_minimum_projete, reutilise tel quel)."""

from decimal import Decimal

from app.finance.cash_risk_policy import calculer_minimum_projete, trouver_semaine_critique
from app.schemas.accounting import DocumentExtractionSnapshot, PostingResult, ValidationResult
from app.schemas.cfo import FinancialFact, FinancialFactDataType
from app.schemas.finance import (
    CashForecast,
    CashPosition,
    FinancialSnapshotWithMargins,
    Payables,
    Receivables,
    WorkingCapital,
)

_CANAL_VERS_NOM_FACT = {
    "BANQUE": "bank_cash",
    "CAISSE": "cash_on_hand",
    "MOBILE_MONEY": "mobile_money_cash",
}

_DEVISE_PAR_DEFAUT = "XOF"
"""Aucun des DTO Financial Intelligence Layer/Cash Forecast ne porte de champ devise (limite deja
documentee en STEP 8/9) - jamais invente comme une valeur calculee, seulement un defaut de
plateforme, identique a app/finance/health_assessment_service.py."""


class FinancialFactExtractor:
    """Instance sans etat (pas de dependance externe) - une methode `extraire` par type de
    resultat de tool, jamais une seule methode fourre-tout qui devinerait la forme des donnees."""

    def extraire(self, tool_name: str, resultat: object) -> list[FinancialFact]:
        if isinstance(resultat, CashPosition):
            return self._depuis_cash_position(tool_name, resultat)
        if isinstance(resultat, FinancialSnapshotWithMargins):
            return self._depuis_snapshot(tool_name, resultat)
        if isinstance(resultat, Receivables):
            return self._depuis_receivables(tool_name, resultat)
        if isinstance(resultat, Payables):
            return self._depuis_payables(tool_name, resultat)
        if isinstance(resultat, WorkingCapital):
            return self._depuis_working_capital(tool_name, resultat)
        if isinstance(resultat, CashForecast):
            return self._depuis_cash_forecast(tool_name, resultat)
        if isinstance(resultat, DocumentExtractionSnapshot):
            return self._depuis_document(tool_name, resultat)
        if isinstance(resultat, ValidationResult | PostingResult):
            return []  # rien de numerique a extraire - la reponse elle-meme fait deja foi.
        return []

    def _depuis_cash_position(self, tool_name: str, cash: CashPosition) -> list[FinancialFact]:
        facts = [self._fact("cash", cash.total, tool_name, as_of=cash.date_reference)]
        par_canal: dict[str, Decimal] = {}
        for compte in cash.par_compte:
            nom = _CANAL_VERS_NOM_FACT.get(compte.canal)
            if nom is not None:
                par_canal[nom] = par_canal.get(nom, Decimal(0)) + compte.solde
        for nom, valeur in par_canal.items():
            facts.append(self._fact(nom, valeur, tool_name, as_of=cash.date_reference))
        return facts

    def _depuis_snapshot(self, tool_name: str, snapshot: FinancialSnapshotWithMargins) -> list[FinancialFact]:
        s = snapshot.snapshot
        periode = f"{s.periode_debut.isoformat()}/{s.periode_fin.isoformat()}"
        facts = [
            self._fact("revenue", s.revenue, tool_name, period=periode),
            self._fact("expenses", s.expenses, tool_name, period=periode),
            self._fact("profit", s.profit, tool_name, period=periode),
            self._fact("cash", s.cash, tool_name, period=periode),
            self._fact("receivables", s.receivables, tool_name, period=periode),
            self._fact("payables", s.payables, tool_name, period=periode),
        ]
        if snapshot.net_margin is not None:
            facts.append(self._fact("net_margin", snapshot.net_margin, tool_name, period=periode))
        return facts

    def _depuis_receivables(self, tool_name: str, receivables: Receivables) -> list[FinancialFact]:
        return [
            self._fact("receivables_total", receivables.total, tool_name, as_of=receivables.as_of),
            self._fact("receivables_current", receivables.current, tool_name, as_of=receivables.as_of),
            self._fact("receivables_overdue", receivables.overdue, tool_name, as_of=receivables.as_of),
        ]

    def _depuis_payables(self, tool_name: str, payables: Payables) -> list[FinancialFact]:
        return [
            self._fact("payables_total", payables.total, tool_name, as_of=payables.as_of),
            self._fact("payables_current", payables.current, tool_name, as_of=payables.as_of),
            self._fact("payables_overdue", payables.overdue, tool_name, as_of=payables.as_of),
        ]

    def _depuis_working_capital(self, tool_name: str, wc: WorkingCapital) -> list[FinancialFact]:
        # status peut etre NOT_AVAILABLE avec des champs numeriques absents - jamais un fact avec
        # une valeur nulle/inventee, on omet simplement le fact plutot que de fabriquer un zero.
        facts = []
        if wc.working_capital is not None:
            facts.append(self._fact("working_capital", wc.working_capital, tool_name, as_of=wc.as_of))
        if wc.inventory is not None:
            facts.append(self._fact("inventory", wc.inventory, tool_name, as_of=wc.as_of))
        return facts

    def _depuis_cash_forecast(self, tool_name: str, forecast: CashForecast) -> list[FinancialFact]:
        facts = [
            self._fact("cash_current", forecast.opening_cash, tool_name, as_of=forecast.as_of,
                       data_type=FinancialFactDataType.ACTUAL),
            self._fact("cash_forecast_closing", forecast.closing_cash, tool_name, as_of=forecast.period_end,
                       data_type=FinancialFactDataType.PREDICTED),
        ]
        if forecast.weeks:
            # Reutilise le meme calcul deterministe que app/finance/cash_risk_policy.py - jamais
            # un second calcul de minimum/semaine critique divergent.
            minimum = calculer_minimum_projete(forecast)
            facts.append(self._fact("cash_forecast_minimum", minimum, tool_name, as_of=forecast.period_end,
                                     data_type=FinancialFactDataType.PREDICTED))
            semaine_critique = trouver_semaine_critique(forecast)
            if semaine_critique is not None:
                facts.append(self._fact("cash_forecast_critical_week", Decimal(semaine_critique), tool_name,
                                         as_of=forecast.period_end, data_type=FinancialFactDataType.PREDICTED))
        return facts

    def _depuis_document(self, tool_name: str, document: DocumentExtractionSnapshot) -> list[FinancialFact]:
        facts = []
        if document.montant_ttc is not None:
            facts.append(self._fact("document_amount_ttc", document.montant_ttc, tool_name, as_of=document.date_piece))
        if document.montant_ht is not None:
            facts.append(self._fact("document_amount_ht", document.montant_ht, tool_name, as_of=document.date_piece))
        if document.montant_tva is not None:
            facts.append(self._fact("document_amount_tva", document.montant_tva, tool_name, as_of=document.date_piece))
        return facts

    @staticmethod
    def _fact(name: str, value: Decimal, source_tool: str, *, period: str | None = None,
              as_of=None, data_type: FinancialFactDataType = FinancialFactDataType.ACTUAL) -> FinancialFact:
        return FinancialFact(
            name=name, value=value, currency=_DEVISE_PAR_DEFAUT, period=period, as_of=as_of,
            source_tool=source_tool, data_type=data_type,
        )
