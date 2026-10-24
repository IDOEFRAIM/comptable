from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    environment: str = "local"
    log_level: str = "INFO"

    # Auth service-a-service avec le coeur Spring Boot
    internal_api_key: str
    core_service_base_url: str = "http://localhost:8080"
    core_service_api_key: str

    # Anthropic - utilise uniquement par le Universal Matching Engine et le Copilot
    # (app/services/anthropic_llm_provider.py, cf. ai_provider ci-dessous). Depuis la mission
    # "extraction/fraude sur Groq", l'extraction VLM et l'analyse de fraude visuelle n'appellent
    # plus Anthropic - voir groq_api_key/groq_model.
    anthropic_api_key: str = ""
    anthropic_model: str = "claude-sonnet-5"

    # Groq (VLM extraction, analyse de fraude visuelle) : seul provider vision utilise par
    # extraction_service.py/fraude_service.py (app/services/groq_client.py). Aucun mode mock a ce
    # niveau (contrairement au Universal Matching Engine) - une cle Groq reelle est necessaire des
    # qu'une piece contenant une image doit etre extraite ou analysee visuellement.
    groq_api_key: str = ""
    groq_model: str = "qwen/qwen3.8-27b"

    # Provider LLM utilise par le Universal Matching Engine et le Copilot (cf.
    # matching_engine.py/copilot_service.py::_get_default_provider) : "mock" (par defaut - aucune
    # cle requise, aucun appel reseau), "anthropic" (necessite anthropic_api_key) ou "groq"
    # (necessite groq_api_key, meme cle que l'extraction/la fraude). L'extraction et la fraude ne
    # sont pas concernees par ce switch : elles appellent toujours Groq directement (pas de mode
    # mock a ce niveau).
    ai_provider: str = "mock"

    # WhatsApp Cloud API (Meta)
    whatsapp_verify_token: str = ""
    whatsapp_app_secret: str = ""
    whatsapp_access_token: str = ""
    whatsapp_phone_number_id: str = ""
    whatsapp_graph_api_version: str = "v21.0"
    whatsapp_graph_api_base_url: str = "https://graph.facebook.com"

    rapprochement_seuil_confiance: float = 0.90

    # Universal Matching Engine (cf. app/services/matching_engine.py)
    matching_auto_match_threshold: float = 0.95
    matching_review_threshold: float = 0.70
    matching_max_candidates_llm: int = 8
    matching_fenetre_jours: int = 30
    # Score composite minimal du meilleur candidat, et ecart minimal avec le second, pour
    # court-circuiter l'appel LLM sur un cas juge "evident" par le scoring deterministe seul
    # (section 24 de la mission : ne jamais appeler Claude inutilement sur un cas evident).
    matching_deterministic_skip_score: float = 0.97
    matching_deterministic_skip_gap: float = 0.20


@lru_cache
def get_settings() -> Settings:
    return Settings()
