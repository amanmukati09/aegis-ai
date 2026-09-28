from fastapi import APIRouter

from ..providers import registry

router = APIRouter(prefix="/v1", tags=["models"])


@router.get("/models")
def list_models() -> dict:
    """Aggregate available models across configured providers.

    Powers the frontend model selector. When no provider key is set yet, returns
    an empty list plus the configured defaults so the UI can still render.
    """
    models = registry.all_models()
    return {
        "default_provider": registry.default_provider,
        "default_model": registry.default_model,
        "available_providers": [p.name for p in registry.available()],
        "models": [{"id": m.id, "provider": m.provider, "label": m.label} for m in models],
    }
