from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)


def test_healthz():
    resp = client.get("/healthz")
    assert resp.status_code == 200
    body = resp.json()
    assert body["status"] == "ok"
    assert body["service"] == "aegisai-ml"


def test_list_models_shape():
    resp = client.get("/v1/models")
    assert resp.status_code == 200
    body = resp.json()
    # Even with no keys configured, the shape and defaults are present.
    assert "default_provider" in body
    assert "models" in body
    assert isinstance(body["models"], list)
