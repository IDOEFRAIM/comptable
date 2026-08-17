from fastapi import APIRouter

from app.api.v1.endpoints import agent, copilot, extraction, fraude, health, matching, rapprochement, whatsapp

api_router = APIRouter()
api_router.include_router(health.router, tags=["health"])
api_router.include_router(extraction.router, tags=["extraction"])
api_router.include_router(fraude.router, tags=["fraude"])
api_router.include_router(rapprochement.router, tags=["rapprochement"])
api_router.include_router(matching.router, tags=["matching"])
api_router.include_router(whatsapp.router, tags=["whatsapp"])
api_router.include_router(agent.router, tags=["agent"])
api_router.include_router(copilot.router, tags=["copilot"])
