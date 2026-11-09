# 数据来源、许可与使用限制

**核验日期：2026-11-07（Asia/Shanghai）。** 本文件记录当时访问的官方元数据、规则或许可证；网页后续可能变化。它不是法律意见，也没有替代数据权利人授权。当前项目**未下载、未复制、未再分发**以下外部数据集；v2 代码支持使用者自行把合法文件放入 `data/external`，但仓库不附带 CSV。端到端烟测使用的是独立编写的 2 行 Olist 结构夹具与 300 行 IEEE 结构夹具，不是官方数据，不能用于效果结论。

## 1. 结论总览

| 来源 | 已核实性质 | 许可/规则核验结论 | 本项目状态与替代方案 |
|---|---|---|---|
| Olist Brazilian E-Commerce Public Dataset | 匿名化的真实商业订单，约 10 万笔，2016–2018；无直接真实欺诈标签 | 官方 Kaggle 元数据 `licenseName=CC BY-NC-SA 4.0` | **非商业用途许可已确认；商业使用阻塞。** v2 可导入本地文件到独立订单流程；须保留署名、许可链接、修改说明和相同方式共享要求 |
| IEEE-CIS Fraud Detection | 真实在线电商交易，训练数据有二元 `isFraud`；非完整订单明细 | 官方规则 B.7 允许竞赛、学术科研和教育等非商业使用；限制向未接受规则者提供数据 | **公开再分发原始/行级数据、商业使用阻塞。** v2 只从使用者本地目录读取，不随项目提供 CSV、行级导出或镜像 |
| Fraud Detection Handbook 主仓库 | 支付卡欺诈教学正文、Notebook 代码 | 代码 GPL-3.0；正文和图片 CC BY-SA 4.0，许可范围不同 | **分类许可已确认。** 当前只参考思想并引用链接，不复制其代码、正文、图片或 Notebook |
| Handbook 独立预生成模拟数据 | `simulated-data-raw` 和 `simulated-data-transformed` 每日 PKL | 两个仓库未找到 LICENSE，GitHub 元数据 `license: null`；不能套用主仓库许可 | **复制/再分发阻塞。** 不下载、不复制 PKL；若需公开分发，先取得作者明确授权，或使用本项目独立虚构生成器 |

## 2. Olist

### 官方事实

- 发布者：Kaggle 账号 `olistbr`；元数据拥有者为 `Olist`。
- 官方说明为真实商业数据的匿名化版本，不是本项目所定义的纯虚构数据。
- 官方目录含订单、订单项、支付、评价、客户、卖家、商品、地理位置、商品类别翻译 9 个 CSV。目录和说明中**没有可直接作为真实欺诈标签的字段**。
- 订单取消、延迟、差评、退款/支付异常、金额异常不能自动改写为欺诈事实或真实监督标签。

### 许可

官方 Kaggle API 明确返回 `CC BY-NC-SA 4.0`。Creative Commons 官方许可摘要列明：分享/改编须适当署名、提供许可链接、标明修改，不得用于商业目的，改编分发须相同方式共享，不得增加额外限制。正式使用前须阅读许可证正文；匿名化不消除许可或隐私义务。

**核验限制：** 元数据和文件目录可匿名读取；工具未实际完成存储下载，因此不声称验证了匿名下载流程。许可元数据核实不等于本项目已具备其他数据保护审批。

证据：

- [官方 Kaggle 数据集页](https://www.kaggle.com/datasets/olistbr/brazilian-ecommerce)
- [官方数据说明与许可元数据 API](https://www.kaggle.com/api/v1/datasets/view/olistbr/brazilian-ecommerce)
- [官方文件目录 API](https://www.kaggle.com/api/v1/datasets/list/olistbr/brazilian-ecommerce)
- [CC BY-NC-SA 4.0 官方说明](https://creativecommons.org/licenses/by-nc-sa/4.0/)
- [CC BY-NC-SA 4.0 法律文本](https://creativecommons.org/licenses/by-nc-sa/4.0/legalcode)

## 3. IEEE-CIS Fraud Detection

### 官方事实

- Kaggle 竞赛 ID 14242，主办方 IEEE Computational Intelligence Society；说明称数据由 Vesta Corporation 提供，来自真实电商在线交易。
- 训练交易数据具有 `isFraud` 二元标签；`train/test_transaction` 与 `train/test_identity` 按 `TransactionID` 关联，identity 并非每笔交易都有。
- 有金额、相对时间、设备、卡片、地址、邮箱域、产品和匿名特征等，但**没有完整电商订单的商品、卖家、履约、物流、评价全链路**。
- `TransactionDT` 是相对参考时间的 timedelta，不是实际下单时间戳；不得伪造为具体日期。

### 使用与再分发条件

官方完整规则 B.7 将 Competition Data 限于非商业目的，包括竞赛、Kaggle 论坛、学术研究与教育；不得向未参加竞赛/未同意规则者传输、复制、发布、再分发或提供数据。规则要求采取合理措施阻止未正式同意者访问，竞赛结束不表示限制失效。

因此：本项目公开分发 IEEE-CIS 原始数据、逐行合并数据、行级特征导出均为**阻塞**。只有每个使用者自行从官方页面登录并接受规则后，才可评估其合规的本地非商业使用。商业使用或公开镜像须取得主办方/Vesta 明确书面授权，否则仍阻塞。

**不要误读：** 规则的 “Winner License: Open-Source” 针对获奖提交/代码，不是竞赛数据的开放许可证。公开可访问竞赛页面不等于数据可再分发。

**核验限制：** 公开数据列表 API 返回 `401 Unauthenticated`；未登录、未接受规则、未下载 CSV，字段结论来自官方数据说明和规则，不是对原始文件的逐列审计。

证据：

- [官方竞赛页面](https://www.kaggle.com/competitions/ieee-fraud-detection)
- [官方竞赛规则](https://www.kaggle.com/competitions/ieee-fraud-detection/rules)
- [官方页面内容 API：规则与数据说明](https://www.kaggle.com/api/i/competitions.PageService/ListPages?competitionId=14242)
- [官方竞赛元数据 API](https://www.kaggle.com/api/i/competitions.CompetitionService/GetCompetition?competitionName=ieee-fraud-detection)

## 4. Fraud Detection Handbook

### 分类许可

官方 README 和 LICENSE 明确：Notebook 代码使用 **GNU GPL v3.0**，正文和图片使用 **CC BY-SA 4.0**。LICENSE 首段排除 prose，不能简单把整个仓库标成 GPL。代码与内容复用须分别遵守许可证义务。

模拟器生成支付卡交易，如 `TRANSACTION_ID`、`TX_DATETIME`、`CUSTOMER_ID`、`TERMINAL_ID`、`TX_AMOUNT`、`TX_FRAUD`；`TX_FRAUD` 是**模拟**欺诈标签，不是真实业务标签。

### 尚存阻塞

官方组织的 `simulated-data-raw`、`simulated-data-transformed` 是独立 PKL 数据仓库；未找到 LICENSE，元数据为 `license: null`。不能把主仓库 GPL/CC 许可自动套用到这些预生成数据。未经许可不复制或再分发其 PKL。

若使用 GPL 模拟器自行生成数据，输出是否受 GPL 覆盖须按其许可证第 2 节的条件具体判断，不能笼统宣称所有输出都是 GPL。要公开再分发生成物，稳妥方案是向作者确认许可或取得书面授权。本项目改用**独立编写的虚构订单种子/生成逻辑**，不复制 Handbook 模拟器或数据。

书中 Worldline 真实数据明确因保密不可共享，不属于公开可选数据。

证据：

- [官方主仓库](https://github.com/Fraud-Detection-Handbook/fraud-detection-handbook)
- [官方 README：分类许可](https://raw.githubusercontent.com/Fraud-Detection-Handbook/fraud-detection-handbook/main/README.md)
- [官方 LICENSE](https://raw.githubusercontent.com/Fraud-Detection-Handbook/fraud-detection-handbook/main/LICENSE)
- [官方模拟器说明](https://fraud-detection-handbook.github.io/fraud-detection-handbook/Chapter_3_GettingStarted/SimulatedDataset.html)
- [原始预生成模拟数据仓库](https://github.com/Fraud-Detection-Handbook/simulated-data-raw)
- [转换后模拟数据仓库](https://github.com/Fraud-Detection-Handbook/simulated-data-transformed)
- [Worldline 数据不可共享说明](https://raw.githubusercontent.com/Fraud-Detection-Handbook/fraud-detection-handbook/main/Chapter_3_GettingStarted/Baseline_RealWorldData.ipynb)
- [GPL-3.0 官方文本](https://www.gnu.org/licenses/gpl-3.0.en.html)
- [CC BY-SA 4.0 官方说明](https://creativecommons.org/licenses/by-sa/4.0/)

## 5. 不可跨越的数据边界

1. Olist 只可作为独立的订单流程数据，IEEE-CIS 只可作为独立的在线交易监督数据，Handbook 是独立教学材料/模拟数据；三者不是同一真实业务。
2. 不得按金额、时间、序号、哈希、ID 等把不同数据集记录或标签拼接；不得用 IEEE-CIS/Handbook 标签给 Olist 订单“补标”。
3. 外部数据必须独立登记来源、批次、许可、用途和保留期限；不得改写为 `SYNTHETIC`。V3 已移除数据库的单源硬限制，改为 `source_system`/`dataset_name`/`license_ref`/`source_status` 显式登记；接入前仍须完成许可审核，IEEE 数据只进独立评测域。
4. 本项目虚构数据使用 `DEMO-*`、`example.invalid`、固定演示时间和明确规则，不借用任何外部记录、标签或个人信息。
5. 许可核验日期之后网页变更、机构/学校的额外规定、隐私与伦理审查仍需使用人确认；未决事项见 [open-issues.md](open-issues.md)。

## 6. v2 运行控制

- 导入只由管理员显式触发，读取本地 `data/external`；缺失文件返回 400，不自动联网下载。
- 导入批次保存文件 SHA-256 幂等键、来源、计数和操作者；不保存密码/Cookie/请求正文。
- Olist 订单与 IEEE 交易分表；IEEE `isFraud` 只存在 `eval_ieee_transaction`，订单表无该字段。
- 训练只接受 `IEEE_CIS`，产物只含特征名、均值/标准差、权重、阈值与指标，不含原始交易行。
- 公开仓库不附带外部 CSV/PKL；`runtime/` 与 `data/external/` 均在忽略清单中。公开再分发/商业使用须另行取得权利人授权。
