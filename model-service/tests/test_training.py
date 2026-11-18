"""IEEE-CIS 独立训练/评测的回归测试，使用合成夹具，不代表真实数据效果。"""
import csv
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.main import app
from app.training import load_artifact, score_with_artifact, train_model


def _write_fixture(path: Path, rows: int = 600, positives: bool = True) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow([
            "TransactionID", "TransactionDT", "TransactionAmt", "isFraud",
            "card1", "card2", "addr1", "dist1", "C1", "D1",
        ])
        for index in range(rows):
            label = 1 if positives and index % 30 == 0 else 0
            amount = 900.0 + index if label else 20.0 + (index % 50)
            writer.writerow([
                index + 1, index * 60, amount, label,
                1000 + index, 200 + index % 5, 300,
                index % 9, index % 13, index % 17,
            ])
    return path


def _write_overlap_fixture(path: Path) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow([
            "TransactionID", "TransactionDT", "TransactionAmt", "isFraud",
            "card1", "card2", "addr1", "dist1", "C1", "D1",
        ])
        for index in range(400):
            label = 1 if index % 30 == 0 else 0
            amount = 900.0 + index if label else 20.0 + (index % 50)
            repeated = 280 <= index < 300 or 340 <= index < 370
            writer.writerow([
                index + 1, index * 60, amount, label,
                1000 if repeated else 1000 + index, 200, 300,
                index % 9, index % 13, index % 17,
            ])
    return path


def test_time_split_is_chronological_and_metrics_are_present(tmp_path):
    data = _write_fixture(tmp_path / "train_transaction.csv")
    result = train_model(data, "ieee-test-v1", tmp_path / "models")
    split = result["metrics"]["split"]
    assert split["trainMaxDt"] < split["validMinDt"]
    assert split["validMaxDt"] < split["testMinDt"]
    assert split["entityGrouping"] == "CARD_ADDR_PURGED"
    assert split["entityOverlap"] == 0
    assert split["trainRows"] + split["validRows"] + split["testRows"] == 600
    for key in ("prAuc", "rocAuc", "recallAtFpr1", "recallAtFpr5",
                "precisionAt100", "brier", "baseRate", "confusion"):
        assert key in result["metrics"]
    assert (tmp_path / "models" / "ieee-test-v1.json").is_file()
    assert result["labelSemantics"] == "MODEL_SCORE_NOT_GROUND_TRUTH"
    assert result["sourceSystem"] == "IEEE_CIS"


def test_artifact_scores_between_zero_and_one(tmp_path):
    data = _write_fixture(tmp_path / "train_transaction.csv")
    train_model(data, "ieee-test-v2", tmp_path / "models")
    artifact = load_artifact("ieee-test-v2", tmp_path / "models")
    scored = score_with_artifact(artifact, {"TransactionAmt": 1200.0, "card1": 1003})
    assert 0.0 <= scored["score"] <= 1.0
    assert scored["decision"] in ("HIGH", "LOW")
    assert scored["labelSemantics"] == "MODEL_SCORE_NOT_GROUND_TRUTH"


def test_entity_overlap_is_purged_from_validation_and_test(tmp_path):
    data = _write_overlap_fixture(tmp_path / "train_transaction.csv")
    result = train_model(data, "ieee-overlap-v1", tmp_path / "models")
    split = result["metrics"]["split"]
    assert split["validRowsDroppedForEntity"] == 20
    assert split["testRowsDroppedForEntity"] == 30
    assert split["trainRows"] + split["validRows"] + split["testRows"] == 350
    assert split["trainMaxDt"] < split["validMinDt"]
    assert split["validMaxDt"] < split["testMinDt"]


def test_missing_positive_segment_is_rejected(tmp_path):
    data = _write_fixture(tmp_path / "train_transaction.csv", positives=False)
    with pytest.raises(ValueError):
        train_model(data, "ieee-no-positive", tmp_path / "models")


def test_missing_required_columns_are_rejected(tmp_path):
    data = tmp_path / "train_transaction.csv"
    data.write_text("TransactionID,TransactionDT\n1,0\n", encoding="utf-8")
    with pytest.raises(ValueError):
        train_model(data, "ieee-bad-columns", tmp_path / "models")


def test_version_cannot_escape_or_reuse_rule_version(tmp_path):
    data = _write_fixture(tmp_path / "train_transaction.csv")
    with pytest.raises(ValueError):
        train_model(data, "synthetic-rule-v1", tmp_path / "models")
    with pytest.raises(ValueError):
        train_model(data, "../escape", tmp_path / "models")


def test_train_endpoint_rejects_path_outside_allowed_root():
    client = TestClient(app)
    response = client.post("/internal/v2/train", json={
        "modelVersion": "ieee-api-v1",
        "dataPath": "C:/outside/train_transaction.csv",
        "sourceSystem": "IEEE_CIS",
    })
    assert response.status_code == 400
    assert "允许目录" in response.json()["detail"]


def test_train_endpoint_rejects_unknown_fields():
    client = TestClient(app)
    response = client.post("/internal/v2/train", json={
        "modelVersion": "ieee-api-v2",
        "dataPath": "data/external/ieee-cis/train_transaction.csv",
        "sourceSystem": "IEEE_CIS",
        "extra": True,
    })
    assert response.status_code == 422
