from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)


def test_detect_returns_valid_shape_without_key():
    # With no provider key configured, the endpoint must still return the fallback
    # shape rather than error out.
    resp = client.post("/v1/detect-anomaly", json={"logs": ["ERROR db timeout"]})
    assert resp.status_code == 200
    body = resp.json()
    assert "anomaly_detected" in body
    assert "severity" in body
    assert "description" in body


def test_diagnose_returns_valid_shape():
    resp = client.post("/v1/diagnose", json={"anomaly": {"severity": "high"}, "logs": ["ERROR"]})
    assert resp.status_code == 200
    body = resp.json()
    assert "root_cause" in body
    assert "confidence" in body
    assert isinstance(body["evidence"], list)


def test_remediation_returns_valid_shape():
    resp = client.post("/v1/remediation/suggest", json={"anomaly": {}, "root_cause": {}})
    assert resp.status_code == 200
    body = resp.json()
    assert isinstance(body["immediate_actions"], list)
    assert isinstance(body["diagnostic_commands"], list)
    assert "escalation_needed" in body
