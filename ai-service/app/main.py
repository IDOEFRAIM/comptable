from fastapi import FastAPI

from app.api.v1.router import api_router

app = FastAPI(title="Comptable AI Service", version="0.1.0")
app.include_router(api_router, prefix="/api/v1")



@app.get("/health", include_in_schema=False)
def health() -> dict[str, str]:
    """Sonde de vie, sans authentification ni appel externe : utilisée par l'administration de la plateforme."""
    return {"status": "ok"}
