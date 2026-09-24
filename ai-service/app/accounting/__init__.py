"""Logique metier AI Accounting (classification, traitement, construction de proposition) -
volontairement separee de app/agent (orchestration LangGraph) et de app/services (transport/HTTP).
Aucune regle comptable definitive ici : la partie double, la resolution de compte et la validation
restent exclusives a Java (AccountingOperationEngine/AccountingAccountResolver)."""
