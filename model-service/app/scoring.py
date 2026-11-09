"""Fixed, deterministic demo rules; not a trained fraud model."""

from .schemas import RiskLevel, RuleCode, ScoreRequest, THRESHOLD


def score_order(request: ScoreRequest) -> tuple[float, RiskLevel, list[RuleCode]]:
    """Return a capped demo score, its risk band, and triggered rule codes."""
    # Integer hundredths avoid binary floating-point drift at risk thresholds.
    score_hundredths = 5
    rule_codes: list[RuleCode] = []

    if request.amount >= 1000:
        score_hundredths += 45
        rule_codes.append(RuleCode.HIGH_AMOUNT)
    if request.paymentCount >= 3:
        score_hundredths += 25
        rule_codes.append(RuleCode.REPEATED_PAYMENT_ATTEMPTS)
    if request.accountAgeDays <= 7:
        score_hundredths += 20
        rule_codes.append(RuleCode.NEW_ACCOUNT)
    if request.transactionCount24h >= 5:
        score_hundredths += 10
        rule_codes.append(RuleCode.HIGH_VELOCITY)

    score = min(score_hundredths, 99) / 100
    if score >= THRESHOLD:
        risk_level = RiskLevel.HIGH
    elif score >= 0.40:
        risk_level = RiskLevel.MEDIUM
    else:
        risk_level = RiskLevel.LOW
    return score, risk_level, rule_codes
