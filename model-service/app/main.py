"""Minimal FastAPI application for deterministic contract scoring."""

from datetime import UTC, datetime
from decimal import Decimal
import json
import math
import os
from pathlib import Path

from fastapi import FastAPI, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from pydantic import ValidationError

from .schemas import (
    HealthResponse,
    LABEL_SEMANTICS,
    MODEL_TYPE,
    MODEL_VERSION,
    ScoreRequest,
    ScoreResponse,
    THRESHOLD,
    TrainedScoreRequest,
    TrainRequest,
)
from .scoring import score_order
from .training import load_artifact, score_with_artifact, train_model

app = FastAPI(
    title="fraud-model",
    description="Fixed demo rules for contract integration; not a trained model.",
    version=MODEL_VERSION,
)


def health_payload() -> HealthResponse:
    return HealthResponse(
        status="UP",
        service="fraud-model",
        modelVersion=MODEL_VERSION,
        modelType=MODEL_TYPE,
        threshold=THRESHOLD,
        labelSemantics=LABEL_SEMANTICS,
    )


@app.get("/health", response_model=HealthResponse, response_model_exclude_none=True)
def health() -> HealthResponse:
    return health_payload()


@app.get("/api/v1/model/health", response_model=HealthResponse)
def model_health() -> HealthResponse:
    return health_payload().model_copy(update={"ready": True})


async def parse_score_request(request: Request) -> ScoreRequest:
    """Keep JSON numbers exact instead of converting DECIMAL through a float."""
    body = await request.body()
    if "json" not in request.headers.get("content-type", "").lower():
        raise RequestValidationError([{
            "type": "model_attributes_type",
            "loc": ("body",),
            "msg": "JSON object required",
            "input": {},
        }])
    try:
        payload = json.loads(
            body,
            parse_float=Decimal,
            parse_constant=lambda value: (_ for _ in ()).throw(ValueError(value)),
        )
    except (ValueError, UnicodeDecodeError) as exception:
        position = exception.pos if isinstance(exception, json.JSONDecodeError) else 0
        raise RequestValidationError([{
            "type": "json_invalid",
            "loc": ("body", position),
            "msg": "JSON decode error",
            "input": {},
            "ctx": {"error": "Invalid JSON"},
        }]) from exception
    try:
        return ScoreRequest.model_validate(payload)
    except ValidationError as exception:
        errors = []
        for error in exception.errors():
            normalized = dict(error)
            location = error["loc"]
            normalized["loc"] = location if location[:1] == ("body",) else ("body",) + location
            errors.append(normalized)
        raise RequestValidationError(errors) from exception


@app.post(
    "/internal/v1/score",
    response_model=ScoreResponse,
    openapi_extra={"requestBody": {
        "required": True,
        "content": {"application/json": {"schema": ScoreRequest.model_json_schema()}},
    }},
)
async def score(request: Request) -> ScoreResponse:
    request_data = await parse_score_request(request)
    score_value, risk_level, rule_codes = score_order(request_data)
    return ScoreResponse(
        orderId=request_data.orderId,
        score=score_value,
        riskLevel=risk_level,
        modelVersion=MODEL_VERSION,
        modelType=MODEL_TYPE,
        threshold=THRESHOLD,
        ruleCodes=rule_codes,
        scoredAt=datetime.now(UTC),
    )


MODEL_ROOT = Path(os.environ.get(
    "MODEL_ARTIFACT_ROOT", Path(__file__).resolve().parents[2] / "runtime" / "models")).resolve()
DATA_ROOT = Path(os.environ.get(
    "MODEL_DATA_ROOT", Path(__file__).resolve().parents[2] / "data" / "external")).resolve()


def _checked_data_path(value: str) -> Path:
    path = Path(value).resolve()
    if not path.is_relative_to(DATA_ROOT) or path.name != "train_transaction.csv":
        raise HTTPException(status_code=400, detail="训练数据路径不在允许目录内")
    if not path.is_file():
        raise HTTPException(status_code=400, detail="训练数据文件不存在")
    return path


@app.post("/internal/v2/train")
def train(request: TrainRequest) -> dict:
    if request.sourceSystem != "IEEE_CIS":
        raise HTTPException(status_code=400, detail="仅支持 IEEE_CIS 独立训练")
    data_path = _checked_data_path(request.dataPath)
    try:
        return train_model(data_path, request.modelVersion, MODEL_ROOT)
    except (ValueError, FileNotFoundError) as exception:
        raise HTTPException(status_code=400, detail=str(exception)) from exception


@app.get("/internal/v2/models")
def models() -> list:
    if not MODEL_ROOT.is_dir():
        return []
    result = []
    for path in sorted(MODEL_ROOT.glob("*.json")):
        try:
            artifact = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            continue
        result.append({
            "modelVersion": artifact.get("modelVersion"),
            "modelType": artifact.get("modelType"),
            "sourceSystem": artifact.get("sourceSystem"),
            "trainedAt": artifact.get("trainedAt"),
            "trainingRows": artifact.get("trainingRows"),
            "positiveRate": artifact.get("positiveRate"),
            "metrics": artifact.get("metrics"),
            "labelSemantics": "MODEL_SCORE_NOT_GROUND_TRUTH",
        })
    return result


@app.post("/internal/v2/score")
def trained_score(request: TrainedScoreRequest) -> dict:
    for value in request.features.values():
        if not math.isfinite(value):
            raise HTTPException(status_code=422, detail="特征值必须有限")
    try:
        artifact = load_artifact(request.modelVersion, MODEL_ROOT)
    except (ValueError, FileNotFoundError) as exception:
        raise HTTPException(status_code=404, detail="模型版本不存在") from exception
    return score_with_artifact(artifact, request.features)

