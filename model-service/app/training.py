"""IEEE-CIS 独立训练/评测。纯标准库实现，避免为演示引入大型依赖。

边界：
- 只读取 IEEE-CIS 交易表；不读取 Olist，不与订单表关联。
- 标签只来自该数据集的 isFraud，不能外推到 Olist。
- 按 TransactionDT 时间切分，绝不随机切分。
- 按 card1+addr1 实体清除跨训练/验证/测试重叠；测试只含训练未见实体。
- 预处理只在训练段拟合；测试段只评估一次。
"""
from __future__ import annotations

import csv
import json
import math
import random
import re
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional, Sequence, Tuple

FEATURE_CANDIDATES: Tuple[str, ...] = (
    "TransactionAmt", "card1", "card2", "card3", "card4", "card5", "card6",
    "addr1", "addr2", "dist1", "dist2",
    "C1", "C2", "C3", "C4", "C5", "C6", "C7", "C8", "C9", "C10",
    "C11", "C12", "C13", "C14",
    "D1", "D2", "D3", "D4", "D5", "D6", "D7", "D8", "D9", "D10",
    "D11", "D12", "D13", "D14", "D15",
)
# 实体键：同一卡号+账单地区的交易不得同时出现在训练与测试段。
# 没有这些列时退化为 TransactionID（每行唯一），保证不会误合并不同实体。
ENTITY_COLUMNS: Tuple[str, ...] = ("card1", "addr1")
MODEL_TYPE = "TRAINED_LOGREG"
SPLIT_STRATEGY = "TIME_70_15_15"
MIN_ROWS = 200
MAX_TRAIN_ROWS = 20000
EPOCHS = 120
LEARNING_RATE = 0.05
L2 = 1e-4
VERSION_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")


def _number(value: Any) -> Optional[float]:
    if value is None:
        return None
    text = str(value).strip()
    if not text:
        return None
    try:
        number = float(text)
    except ValueError:
        return None
    if math.isnan(number) or math.isinf(number):
        return None
    return number


def _sigmoid(value: float) -> float:
    if value >= 0:
        z = math.exp(-value)
        return 1.0 / (1.0 + z)
    z = math.exp(value)
    return z / (1.0 + z)


def load_rows(data_path: Path) -> Tuple[List[Tuple[int, int, int, List[Optional[float]]]], List[str], int]:
    """返回 (dt, transaction_id, label, features), 特征名, 跳过行数。"""
    if not data_path.is_file():
        raise FileNotFoundError("IEEE-CIS train_transaction.csv 不存在")
    rows: List[Tuple[int, int, int, List[Optional[float]]]] = []
    skipped = 0
    with data_path.open("r", encoding="utf-8-sig", newline="") as handle:
        reader = csv.DictReader(handle)
        fieldnames = reader.fieldnames or []
        required = {"TransactionID", "TransactionDT", "TransactionAmt", "isFraud"}
        if not required.issubset(set(fieldnames)):
            raise ValueError("缺少 TransactionID/TransactionDT/TransactionAmt/isFraud 列")
        features = [name for name in FEATURE_CANDIDATES if name in fieldnames]
        if "TransactionAmt" not in features:
            raise ValueError("TransactionAmt 必须作为特征")
        for record in reader:
            try:
                transaction_id = int(float(record["TransactionID"]))
                transaction_dt = int(float(record["TransactionDT"]))
                amount = float(record["TransactionAmt"])
                label = int(float(record["isFraud"]))
            except (TypeError, ValueError):
                skipped += 1
                continue
            if label not in (0, 1) or amount < 0 or transaction_dt < 0:
                skipped += 1
                continue
            vector = [_number(record.get(name)) for name in features]
            rows.append((transaction_dt, transaction_id, label, vector))
    rows.sort(key=lambda item: (item[0], item[1]))
    return rows, features, skipped


def _fit(train: Sequence[Tuple[int, int, int, List[Optional[float]]]], width: int) -> Tuple[List[float], List[float]]:
    means: List[float] = []
    scales: List[float] = []
    for index in range(width):
        values = [row[3][index] for row in train if row[3][index] is not None]
        mean = sum(values) / len(values) if values else 0.0
        variance = sum((value - mean) ** 2 for value in values) / len(values) if values else 0.0
        scale = math.sqrt(variance)
        means.append(mean)
        scales.append(scale if scale > 1e-9 else 1.0)
    return means, scales


def _transform(row: Sequence[Optional[float]], means: Sequence[float], scales: Sequence[float]) -> List[float]:
    return [0.0 if value is None else (value - mean) / scale
            for value, mean, scale in zip(row, means, scales)]


def _train_logreg(xs: Sequence[Sequence[float]], ys: Sequence[int]) -> Tuple[List[float], float]:
    width = len(xs[0])
    weights = [0.0] * width
    bias = 0.0
    positives = sum(ys)
    negatives = len(ys) - positives
    if positives == 0:
        raise ValueError("训练段没有正样本，无法训练")
    positive_weight = min(50.0, negatives / positives)
    order = list(range(len(xs)))
    generator = random.Random(42)
    for _ in range(EPOCHS):
        generator.shuffle(order)
        for index in order:
            row = xs[index]
            label = ys[index]
            score = bias + sum(weight * value for weight, value in zip(weights, row))
            prediction = _sigmoid(score)
            sample_weight = positive_weight if label == 1 else 1.0
            error = (prediction - label) * sample_weight
            for position in range(width):
                weights[position] -= LEARNING_RATE * (error * row[position] + L2 * weights[position])
            bias -= LEARNING_RATE * error
    return weights, bias


def _scores(vectors: Sequence[Sequence[float]], weights: Sequence[float], bias: float) -> List[float]:
    result: List[float] = []
    for row in vectors:
        result.append(_sigmoid(bias + sum(weight * value for weight, value in zip(weights, row))))
    return result


def _confusion(labels: Sequence[int], scores: Sequence[float], threshold: float) -> Dict[str, int]:
    tp = fp = tn = fn = 0
    for label, score in zip(labels, scores):
        predicted = 1 if score >= threshold else 0
        if label == 1 and predicted == 1:
            tp += 1
        elif label == 1:
            fn += 1
        elif predicted == 1:
            fp += 1
        else:
            tn += 1
    return {"tp": tp, "fp": fp, "tn": tn, "fn": fn}


def _f1(confusion: Dict[str, int]) -> float:
    tp, fp, fn = confusion["tp"], confusion["fp"], confusion["fn"]
    if tp == 0:
        return 0.0
    precision = tp / (tp + fp)
    recall = tp / (tp + fn)
    return 2 * precision * recall / (precision + recall)


def choose_threshold(labels: Sequence[int], scores: Sequence[float]) -> Tuple[float, Dict[str, int]]:
    best_threshold = 0.5
    best_confusion = _confusion(labels, scores, best_threshold)
    best_f1 = _f1(best_confusion)
    for step in range(2, 99):
        threshold = step / 100.0
        confusion = _confusion(labels, scores, threshold)
        value = _f1(confusion)
        if value > best_f1 + 1e-12:
            best_f1 = value
            best_threshold = threshold
            best_confusion = confusion
    return best_threshold, best_confusion


def _roc_auc(labels: Sequence[int], scores: Sequence[float]) -> Optional[float]:
    positives = [score for label, score in zip(labels, scores) if label == 1]
    negatives = [score for label, score in zip(labels, scores) if label == 0]
    if not positives or not negatives:
        return None
    ordered = sorted(zip(scores, labels), key=lambda item: item[0])
    ranks: List[float] = [0.0] * len(ordered)
    position = 0
    while position < len(ordered):
        end = position
        while end + 1 < len(ordered) and ordered[end + 1][0] == ordered[position][0]:
            end += 1
        average_rank = (position + end + 2) / 2.0  # 1-based average rank
        for index in range(position, end + 1):
            ranks[index] = average_rank
        position = end + 1
    positive_rank_sum = sum(rank for rank, (_, label) in zip(ranks, ordered) if label == 1)
    n_pos = len(positives)
    n_neg = len(negatives)
    return (positive_rank_sum - n_pos * (n_pos + 1) / 2.0) / (n_pos * n_neg)


def _average_precision(labels: Sequence[int], scores: Sequence[float]) -> Optional[float]:
    total_positives = sum(labels)
    if total_positives == 0:
        return None
    ordered = sorted(zip(scores, labels), key=lambda item: item[0], reverse=True)
    tp = 0
    fp = 0
    previous_recall = 0.0
    area = 0.0
    for score, label in ordered:
        if label == 1:
            tp += 1
        else:
            fp += 1
        recall = tp / total_positives
        precision = tp / (tp + fp)
        area += (recall - previous_recall) * precision
        previous_recall = recall
    return area


def _recall_at_fpr(labels: Sequence[int], scores: Sequence[float], target_fpr: float) -> Optional[float]:
    total_positives = sum(labels)
    total_negatives = len(labels) - total_positives
    if total_positives == 0 or total_negatives == 0:
        return None
    ordered = sorted(zip(scores, labels), key=lambda item: item[0], reverse=True)
    tp = 0
    fp = 0
    best = 0.0
    for _, label in ordered:
        if label == 1:
            tp += 1
        else:
            fp += 1
        if fp / total_negatives <= target_fpr:
            best = max(best, tp / total_positives)
    return best


def _precision_at_k(labels: Sequence[int], scores: Sequence[float], k: int) -> Optional[float]:
    if not labels:
        return None
    ordered = sorted(zip(scores, labels), key=lambda item: item[0], reverse=True)[:k]
    return sum(label for _, label in ordered) / len(ordered) if ordered else None


def _brier(labels: Sequence[int], scores: Sequence[float]) -> float:
    return sum((score - label) ** 2 for label, score in zip(labels, scores)) / len(labels)


def _metrics(labels: Sequence[int], scores: Sequence[float], threshold: float) -> Dict[str, Any]:
    confusion = _confusion(labels, scores, threshold)
    positives = sum(labels)
    return {
        "testRows": len(labels),
        "testPositives": positives,
        "baseRate": positives / len(labels) if labels else None,
        "prAuc": _average_precision(labels, scores),
        "rocAuc": _roc_auc(labels, scores),
        "recallAtFpr1": _recall_at_fpr(labels, scores, 0.01),
        "recallAtFpr5": _recall_at_fpr(labels, scores, 0.05),
        "precisionAt100": _precision_at_k(labels, scores, 100),
        "brier": _brier(labels, scores),
        "threshold": threshold,
        "confusion": confusion,
    }


def _entity_key(row: Tuple[int, int, int, List[Optional[float]]], features: Sequence[str]) -> str:
    positions = [index for index, name in enumerate(features) if name in ENTITY_COLUMNS]
    if not positions:
        return f"transaction:{row[1]}"
    parts = []
    for index in positions:
        value = row[3][index]
        parts.append(f"{features[index]}={'' if value is None else repr(value)}")
    return "|".join(parts)


def train_model(data_path: Path, model_version: str, model_root: Path) -> Dict[str, Any]:
    if not VERSION_PATTERN.match(model_version) or model_version == "synthetic-rule-v1":
        raise ValueError("modelVersion 格式不合法或复用了 DEMO_RULE 版本")
    rows, features, skipped = load_rows(data_path)
    if len(rows) < MIN_ROWS:
        raise ValueError(f"有效行数不足 {MIN_ROWS}，无法做时间切分评测")
    train_end = int(len(rows) * 0.70)
    valid_end = int(len(rows) * 0.85)

    # 先按时间切候选段，再按实体清除跨段重叠；测试段不得出现训练/验证实体。
    train_rows = rows[:train_end]
    train_groups = {_entity_key(row, features) for row in train_rows}
    valid_candidates = rows[train_end:valid_end]
    valid_rows = [row for row in valid_candidates
                  if _entity_key(row, features) not in train_groups]
    valid_groups = {_entity_key(row, features) for row in valid_rows}
    test_candidates = rows[valid_end:]
    test_rows = [row for row in test_candidates
                 if _entity_key(row, features) not in train_groups
                 and _entity_key(row, features) not in valid_groups]
    valid_dropped = len(valid_candidates) - len(valid_rows)
    test_dropped = len(test_candidates) - len(test_rows)
    if not train_rows or not valid_rows or not test_rows:
        raise ValueError("按时间+实体隔离后存在空分段；请提供更多独立实体或调整数据")
    if not any(row[2] for row in test_rows) or not any(row[2] for row in valid_rows):
        raise ValueError("实体隔离后验证/测试段缺少正样本；当前数据不满足演示训练条件")

    train_labels = [row[2] for row in train_rows]
    valid_labels = [row[2] for row in valid_rows]
    test_labels = [row[2] for row in test_rows]
    if sum(train_labels) == 0 or sum(valid_labels) == 0 or sum(test_labels) == 0:
        raise ValueError("每个时间分段都必须包含正样本；当前数据不满足演示训练条件")

    means, scales = _fit(train_rows, len(features))
    train_x = [_transform(row[3], means, scales) for row in train_rows]
    valid_x = [_transform(row[3], means, scales) for row in valid_rows]
    test_x = [_transform(row[3], means, scales) for row in test_rows]

    # 纯 Python SGD 的成本随行数增长；仅对训练段做确定性抽样，验证/测试保持完整。
    if len(train_x) > MAX_TRAIN_ROWS:
        stride = math.ceil(len(train_x) / MAX_TRAIN_ROWS)
        train_x = train_x[::stride]
        train_labels = train_labels[::stride]

    weights, bias = _train_logreg(train_x, train_labels)
    valid_scores = _scores(valid_x, weights, bias)
    threshold, _ = choose_threshold(valid_labels, valid_scores)
    test_scores = _scores(test_x, weights, bias)
    metrics = _metrics(test_labels, test_scores, threshold)
    split = {
        "strategy": SPLIT_STRATEGY,
        "entityGrouping": "CARD_ADDR_PURGED",
        "entityColumns": [name for name in ENTITY_COLUMNS if name in features],
        "entityOverlap": 0,
        "trainRows": len(train_rows),
        "validRows": len(valid_rows),
        "testRows": len(test_rows),
        "validRowsDroppedForEntity": valid_dropped,
        "testRowsDroppedForEntity": test_dropped,
        "trainMaxDt": train_rows[-1][0],
        "validMinDt": valid_rows[0][0],
        "validMaxDt": valid_rows[-1][0],
        "testMinDt": test_rows[0][0],
        "testMaxDt": test_rows[-1][0],
    }

    model_root.mkdir(parents=True, exist_ok=True)
    artifact_path = model_root / f"{model_version}.json"
    artifact = {
        "modelVersion": model_version,
        "modelType": MODEL_TYPE,
        "sourceSystem": "IEEE_CIS",
        "labelSemantics": "MODEL_SCORE_NOT_GROUND_TRUTH",
        "features": features,
        "means": means,
        "scales": scales,
        "weights": weights,
        "bias": bias,
        "threshold": threshold,
        "trainedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "trainingRows": len(train_x),
        "sourceRows": len(rows),
        "positiveRate": sum(train_labels) / len(train_labels),
        "skippedRows": skipped,
        "splitStrategy": SPLIT_STRATEGY,
        "split": split,
        "metrics": metrics,
    }
    artifact_path.write_text(json.dumps(artifact, ensure_ascii=False, indent=2), encoding="utf-8")
    return {
        "modelVersion": model_version,
        "modelType": MODEL_TYPE,
        "sourceSystem": "IEEE_CIS",
        "artifactPath": f"runtime/models/{model_version}.json",
        "featureSchema": features,
        "trainedAt": artifact["trainedAt"],
        "trainingRows": len(train_x),
        "positiveRate": artifact["positiveRate"],
        "metrics": dict(metrics,
                        trainRows=len(train_x),
                        validRows=len(valid_rows),
                        skippedRows=skipped,
                        split=split),
        "labelSemantics": "MODEL_SCORE_NOT_GROUND_TRUTH",
    }


def load_artifact(model_version: str, model_root: Path) -> Dict[str, Any]:
    if not VERSION_PATTERN.match(model_version):
        raise ValueError("modelVersion 格式不合法")
    path = model_root / f"{model_version}.json"
    if not path.is_file():
        raise FileNotFoundError("模型版本不存在")
    return json.loads(path.read_text(encoding="utf-8"))


def score_with_artifact(artifact: Dict[str, Any], features: Dict[str, float]) -> Dict[str, Any]:
    names = artifact["features"]
    means = artifact["means"]
    scales = artifact["scales"]
    weights = artifact["weights"]
    bias = artifact["bias"]
    total = bias
    for name, mean, scale, weight in zip(names, means, scales, weights):
        value = features.get(name)
        normalized = 0.0 if value is None else (float(value) - mean) / scale
        total += weight * normalized
    score = _sigmoid(total)
    threshold = float(artifact["threshold"])
    return {
        "modelVersion": artifact["modelVersion"],
        "modelType": artifact["modelType"],
        "sourceSystem": artifact["sourceSystem"],
        "score": round(score, 6),
        "threshold": threshold,
        "decision": "HIGH" if score >= threshold else "LOW",
        "labelSemantics": "MODEL_SCORE_NOT_GROUND_TRUTH",
    }
