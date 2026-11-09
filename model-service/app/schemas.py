"""Request and response schemas for the demo model service."""

from datetime import datetime
from decimal import Decimal
from enum import Enum
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

MODEL_VERSION = "synthetic-rule-v1"
MODEL_TYPE = "DEMO_RULE"
THRESHOLD = 0.70
LABEL_SEMANTICS = "RISK_SCORE_NOT_GROUND_TRUTH"


class StrictModel(BaseModel):
    """Reject unknown fields instead of silently accepting them."""

    model_config = ConfigDict(extra="forbid")


class ScoreRequest(StrictModel):
    orderId: str = Field(min_length=1, max_length=64)
    amount: Decimal = Field(
        gt=0,
        # FastAPI uses this JSON numeric bound in its generated OpenAPI schema.
        le=Decimal("9999999999999999.99"),
        max_digits=18,
        decimal_places=2,
        allow_inf_nan=False,
    )
    currency: Literal["CNY"]
    paymentCount: int = Field(ge=1, le=100000, strict=True)
    accountAgeDays: int = Field(ge=0, le=365000, strict=True)
    transactionCount24h: int = Field(ge=1, le=100000, strict=True)


class RiskLevel(str, Enum):
    LOW = "LOW"
    MEDIUM = "MEDIUM"
    HIGH = "HIGH"


class RuleCode(str, Enum):
    HIGH_AMOUNT = "HIGH_AMOUNT"
    REPEATED_PAYMENT_ATTEMPTS = "REPEATED_PAYMENT_ATTEMPTS"
    NEW_ACCOUNT = "NEW_ACCOUNT"
    HIGH_VELOCITY = "HIGH_VELOCITY"


class HealthResponse(StrictModel):
    status: Literal["UP"]
    service: Literal["fraud-model"]
    modelVersion: Literal[MODEL_VERSION]
    modelType: Literal[MODEL_TYPE]
    threshold: Literal[THRESHOLD]
    labelSemantics: Literal[LABEL_SEMANTICS]
    ready: bool | None = None


class ScoreResponse(StrictModel):
    orderId: str
    score: float
    riskLevel: RiskLevel
    modelVersion: Literal[MODEL_VERSION]
    modelType: Literal[MODEL_TYPE]
    threshold: Literal[THRESHOLD]
    ruleCodes: list[RuleCode]
    scoredAt: datetime
    labelSemantics: Literal[LABEL_SEMANTICS] = LABEL_SEMANTICS


class TrainRequest(StrictModel):
    """v2：IEEE-CIS 独立训练请求；路径由 Java 服务端生成，不接受任意公网路径。"""

    modelVersion: str = Field(min_length=1, max_length=64, pattern=r"^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
    dataPath: str = Field(min_length=1, max_length=512)
    sourceSystem: Literal["IEEE_CIS"]


class TrainedScoreRequest(StrictModel):
    modelVersion: str = Field(min_length=1, max_length=64, pattern=r"^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
    features: dict[str, float] = Field(default_factory=dict)
