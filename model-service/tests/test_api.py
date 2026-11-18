"""Contract tests for the deterministic demo scoring API."""

from collections.abc import Iterator
import json
from datetime import UTC, datetime
from typing import Any

import pytest
from fastapi.testclient import TestClient

from app.main import app

HEALTH = {
    "status": "UP",
    "service": "fraud-model",
    "modelVersion": "synthetic-rule-v1",
    "modelType": "DEMO_RULE",
    "threshold": 0.70,
    "labelSemantics": "RISK_SCORE_NOT_GROUND_TRUTH",
}


@pytest.fixture
def client() -> Iterator[TestClient]:
    with TestClient(app) as test_client:
        yield test_client


def valid_payload() -> dict[str, Any]:
    return {
        "orderId": "order-123",
        "amount": "999.99",
        "currency": "CNY",
        "paymentCount": 1,
        "accountAgeDays": 30,
        "transactionCount24h": 1,
    }


def test_health(client: TestClient) -> None:
    response = client.get("/health")

    assert response.status_code == 200
    assert response.json() == HEALTH


def test_model_health(client: TestClient) -> None:
    response = client.get("/api/v1/model/health")

    assert response.status_code == 200
    assert response.json() == {**HEALTH, "ready": True}


def test_low_score(client: TestClient) -> None:
    response = client.post("/internal/v1/score", json=valid_payload())

    assert response.status_code == 200
    body = response.json()
    assert set(body) == {
        "orderId",
        "score",
        "riskLevel",
        "modelVersion",
        "modelType",
        "threshold",
        "ruleCodes",
        "scoredAt",
        "labelSemantics",
    }
    assert body["orderId"] == "order-123"
    assert body["score"] == pytest.approx(0.05)
    assert body["riskLevel"] == "LOW"
    assert body["modelVersion"] == "synthetic-rule-v1"
    assert body["modelType"] == "DEMO_RULE"
    assert body["threshold"] == 0.70
    assert body["ruleCodes"] == []
    scored_at = datetime.fromisoformat(body["scoredAt"])
    assert scored_at.utcoffset() == UTC.utcoffset(scored_at)


@pytest.mark.parametrize(
    ("updates", "expected_score", "expected_level", "expected_codes"),
    [
        pytest.param(
            {"paymentCount": 3, "transactionCount24h": 5},
            0.40,
            "MEDIUM",
            ["REPEATED_PAYMENT_ATTEMPTS", "HIGH_VELOCITY"],
            id="medium-threshold",
        ),
        pytest.param(
            {"amount": "1000", "accountAgeDays": 7},
            0.70,
            "HIGH",
            ["HIGH_AMOUNT", "NEW_ACCOUNT"],
            id="high-threshold",
        ),
        pytest.param(
            {
                "amount": "1000",
                "paymentCount": 3,
                "accountAgeDays": 7,
                "transactionCount24h": 5,
            },
            0.99,
            "HIGH",
            [
                "HIGH_AMOUNT",
                "REPEATED_PAYMENT_ATTEMPTS",
                "NEW_ACCOUNT",
                "HIGH_VELOCITY",
            ],
            id="score-cap",
        ),
    ],
)
def test_rule_boundaries(
    client: TestClient,
    updates: dict[str, Any],
    expected_score: float,
    expected_level: str,
    expected_codes: list[str],
) -> None:
    payload = valid_payload() | updates

    response = client.post("/internal/v1/score", json=payload)

    assert response.status_code == 200
    body = response.json()
    assert body["score"] == pytest.approx(expected_score)
    assert body["riskLevel"] == expected_level
    assert body["ruleCodes"] == expected_codes


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("orderId", ""),
        ("orderId", "x" * 65),
        ("orderId", 123),
        ("amount", "0"),
        ("amount", "-0.01"),
        ("amount", "not-a-number"),
        ("amount", "Infinity"),
        ("amount", "NaN"),
        ("amount", "10000000000000000"),
        ("amount", "99999999999999999.99"),
        ("amount", "1.001"),
        ("currency", "USD"),
        ("paymentCount", 0),
        ("accountAgeDays", -1),
        ("transactionCount24h", 0),
        ("transactionCount24h", 1.5),
        ("paymentCount", 1.5),
        ("accountAgeDays", 365001),
        ("paymentCount", 100001),
    ],
)
def test_invalid_input(client: TestClient, field: str, value: Any) -> None:
    payload = valid_payload() | {field: value}

    response = client.post("/internal/v1/score", json=payload)

    assert response.status_code == 422
    assert response.json()["detail"][0]["loc"] == ["body", field]


def test_mysql_decimal_upper_boundary(client: TestClient) -> None:
    # Send the exact JSON number Java emits, not a Python float approximation.
    numeric_payload = (
        '{"orderId":"DEMO-BOUNDARY","amount":9999999999999999.99,'
        '"currency":"CNY","paymentCount":1,"accountAgeDays":94,'
        '"transactionCount24h":1}'
    )
    response = client.post(
        "/internal/v1/score",
        content=numeric_payload,
        headers={"content-type": "application/json"},
    )

    assert response.status_code == 200
    assert response.json()["score"] == pytest.approx(0.50)
    assert response.json()["riskLevel"] == "MEDIUM"
    assert response.json()["ruleCodes"] == ["HIGH_AMOUNT"]


def test_score_requires_json_content_type(client: TestClient) -> None:
    response = client.post(
        "/internal/v1/score",
        content=json.dumps(valid_payload()),
        headers={"content-type": "text/plain"},
    )

    assert response.status_code == 422
    assert response.json()["detail"][0]["loc"] == ["body"]


def test_invalid_json_has_body_error_location(client: TestClient) -> None:
    response = client.post(
        "/internal/v1/score",
        content="{invalid-json",
        headers={"content-type": "application/json"},
    )

    assert response.status_code == 422
    assert response.json()["detail"][0]["loc"][0] == "body"


def test_openapi_keeps_the_score_request_schema(client: TestClient) -> None:
    response = client.get("/openapi.json")

    assert response.status_code == 200
    schema = response.json()["paths"]["/internal/v1/score"]["post"]["requestBody"]
    assert schema["required"] is True
    assert "amount" in schema["content"]["application/json"]["schema"]["properties"]


def test_missing_required_field(client: TestClient) -> None:
    payload = valid_payload()
    del payload["amount"]

    response = client.post("/internal/v1/score", json=payload)

    assert response.status_code == 422


def test_extra_field_is_rejected(client: TestClient) -> None:
    payload = valid_payload() | {"unexpected": "value"}

    response = client.post("/internal/v1/score", json=payload)

    assert response.status_code == 422
    assert response.json()["detail"][0]["type"] == "extra_forbidden"
    assert response.json()["detail"][0]["loc"] == ["body", "unexpected"]


def test_score_is_deterministic_except_timestamp(client: TestClient) -> None:
    payload = valid_payload() | {"amount": "1000", "paymentCount": 3, "accountAgeDays": 8}

    first = client.post("/internal/v1/score", json=payload).json()
    second = client.post("/internal/v1/score", json=payload).json()

    first_without_time = {key: value for key, value in first.items() if key != "scoredAt"}
    second_without_time = {
        key: value for key, value in second.items() if key != "scoredAt"
    }
    assert first_without_time == second_without_time
    assert datetime.fromisoformat(first["scoredAt"]).tzinfo is not None
    assert datetime.fromisoformat(second["scoredAt"]).tzinfo is not None
