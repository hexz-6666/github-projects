# fraud-model

FastAPI 演示服务，包含两层：

- **v1 固定规则评分**：`POST /internal/v1/score`，确定性规则，仅用于契约联调；不是训练模型，分数不是真实欺诈概率。
- **v2 IEEE-CIS 训练/评测**：`POST /internal/v2/train`、`POST /internal/v2/score`、`GET /internal/v2/models`；按 `TransactionDT` 时间切分，产物写入 `runtime/models/`。

服务不访问 MySQL、不处理复核、不记录请求正文；输出不含输入敏感值。

## 环境与安装

需要 Python 3.12。项目根目录统一使用根 `.venv`；本目录独立虚拟环境只用于单独调试。

```powershell
py -3.12 -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -r requirements-dev.txt
```

## 启动

```powershell
uvicorn app.main:app --host 127.0.0.1 --port 8000
```

健康：

- `GET /health`：v1 健康，不含 `ready`。
- `GET /api/v1/model/health`：增加 `ready: true`。

## v1 规则评分

规则从 0.05 开始累加，最高 0.99：金额 ≥1000 +0.45，支付次数 ≥3 +0.25，账户年龄 ≤7 天 +0.20，24h 交易 ≥5 +0.10。分数 ≥0.70 `HIGH`，≥0.40 `MEDIUM`，否则 `LOW`。

## v2 训练（仅 IEEE-CIS）

Java 调用时传入绝对路径；独立调用示例：

```powershell
$body = @{
  modelVersion = "ieee-logreg-v1"
  dataPath = "E:/beysesji/data/external/ieee-cis/train_transaction.csv"
  sourceSystem = "IEEE_CIS"
} | ConvertTo-Json
Invoke-RestMethod -Uri "http://127.0.0.1:8000/internal/v2/train" `
  -Method Post -ContentType "application/json" -Body $body
```

约束：

- `dataPath` 必须位于 `MODEL_DATA_ROOT`（默认项目根 `data/external`）下，文件名为 `train_transaction.csv`。
- 有效行 ≥200，且训练/验证/测试每段都要有正样本。
- 70/15/15 按时间排序切分；预处理只在训练段拟合；阈值只在验证段选；测试段只评估一次。
- 纯标准库逻辑回归；训练段 >20,000 行时确定性抽样，验证/测试完整。
- 指标：PR-AUC、ROC-AUC、Recall@FPR 1%/5%、Precision@100、Brier、混淆矩阵、基率。
- 产物 `runtime/models/<version>.json`；版本不能是 `synthetic-rule-v1`，不能路径穿越。

## 评分已训练模型

```powershell
$body = @{
  modelVersion = "ieee-logreg-v1"
  features = @{ TransactionAmt = 1200.0; card1 = 1003 }
} | ConvertTo-Json
Invoke-RestMethod -Uri "http://127.0.0.1:8000/internal/v2/score" `
  -Method Post -ContentType "application/json" -Body $body
```

## 测试

从项目根目录：

```powershell
.\.venv\Scripts\python.exe -m pytest model-service\tests -q
```

当前 **40 项**：v1 规则/边界/严格字段/确定性 32 项 + v2 时间/实体切分、指标、产物、非法数据 8 项。合成夹具的 1.0 指标只证明管线，不代表真实效果。

## 环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| `MODEL_DATA_ROOT` | 项目根 `data/external` | 允许训练读取的根 |
| `MODEL_ARTIFACT_ROOT` | 项目根 `runtime/models` | 产物根 |

只绑定 `127.0.0.1`；内部端点无独立鉴权，依赖 Java 网关与本机边界，不能直接暴露公网。
