# ai-delivery-guard

**AI 输出质量门禁** —— 把"AI 生成的业务结论能不能用"做成 8 条可执行、可测试的判定规则。

Java 与 Go 双实现，同一批断言，输出逐字段一致。

---

## 这个仓库解决什么问题

AI 生成的业务代码，最危险的不是报错，是**看起来正常**：

| 现象 | 会抛异常吗 | 后果 |
|---|---|---|
| 请求 12 项，返回 8 项，没有说明 | ❌ | 系统以为这批只有 8 项 |
| 结论对，但"证据"是编的（原文没有那句） | ❌ | 合规结论无法追溯 |
| 置信度 0.71，`result` 却填了 `PASS` | ❌ | 低把握的结论被当成确定结论落库 |
| 返回了请求清单里不存在的 `9.9` 项 | ❌ | 脏数据污染检查单 |
| `max_tokens` 截断，但前半段仍是合法 JSON | ❌ | **不完整的输出比错误的输出更危险** |

门禁的作用：把这些"看起来正常"的判定，变成**显式的、带原因码的拒绝**。

---

## 快速开始

### Go

```bash
cd guard-go

# 跑测试（18 个用例）
go test ./... -v

# 不启服务，直接看一份内置演示的判定结果
go run . -selfcheck

# 起 HTTP 服务
go run . -addr 127.0.0.1:8090
curl http://127.0.0.1:8090/demo
```

### Java（JDK 17+，无需 Maven）

```bat
cd guard-java
run.bat            :: 自测 17 条 + 打印 demo
run.bat serve      :: 起 HTTP 服务，127.0.0.1:8091
run.bat case.json  :: 校验指定文件
```

两个服务同时起时，`curl 127.0.0.1:8090/demo` 和 `curl 127.0.0.1:8091/demo` 的输出应当**逐字段一致**。

### 接口契约

```
GET  /health          → {"status":"UP"}
GET  /demo            → 内置示例的判定结果
POST /guard/check     → body: Request JSON（模型原始输出），返回 Response JSON
```

请求：

```json
{
  "finishReason": "stop",
  "sourceText": "现场检查记录：……有效期至 2025 年 5 月 27 日……",
  "requestItems": ["1.1", "1.2", "2.2"],
  "forbiddenItems": ["2.2"],
  "verdicts": [
    {"itemId": "1.1", "result": "PASS", "evidence": "有效期至 2025 年 5 月 27 日", "confidence": 0.96}
  ]
}
```

响应（对上面这份 demo 数据的真实输出）：

```json
{
  "ok": true,
  "rejectedAll": false,
  "incomplete": false,
  "accepted": [
    {"itemId": "1.1", "result": "PASS", "evidence": "有效期至 2025 年 5 月 27 日", "confidence": 0.96}
  ],
  "rejected": [
    {"itemId": "1.2", "reasonCode": "E_LOW_CONFIDENCE", "detail": "confidence 低于阈值，结论不下发，转人工补充"},
    {"itemId": "1.3", "reasonCode": "E_EVIDENCE_NOT_FOUND", "detail": "证据在原始文本中不存在（逐字匹配失败），判定为模型编造"},
    {"itemId": "2.2", "reasonCode": "E_FORBIDDEN_ITEM", "detail": "该项属人工判定范围，模型结论不予采用"},
    {"itemId": "9.9", "reasonCode": "E_ITEM_NOT_REQUESTED", "detail": "返回了本次请求清单外的检查项，视为编造或串项"}
  ],
  "stats": {"requested": 4, "accepted": 1, "rejected": 3, "outOfScope": 1}
}
```

`rejectedAll` 与 `incomplete` 是**两个独立位**，这是整套设计的核心：

- `rejectedAll=true`：技术故障（截断、空输出）→ 整包不可用
- `incomplete=true`：业务不确定（有项判不了）→ 其余项照常交付

把它们混成一个"失败"布尔值，结果就是"一项判不了，整批 422"，可用率归零。

---

## 八条规则

| 编号 | 规则 | 原因码 |
|---|---|---|
| G0 | `finish_reason` 必须为 `stop`，否则整包拒绝 | `E_TRUNCATED` |
| G1 | `itemId` 必须在请求清单内 | `E_ITEM_NOT_REQUESTED` |
| G2 | `result` 必须是 `PASS/FAIL/UNKNOWN/NA` 之一 | `E_RESULT_NOT_ENUM` |
| G3 | `PASS/FAIL` 的 `confidence ≥ 0.90`；`UNKNOWN` + 0.95 以上判自相矛盾 | `E_LOW_CONFIDENCE` / `E_CONF_CONFLICT` |
| G4 | `evidence` 必须逐字出现在原文中，且 ≥ 4 字 | `E_EVIDENCE_NOT_FOUND` / `E_EVIDENCE_TOO_SHORT` |
| G5 | 每个请求项都必须有结论，缺则标记不完整 | `E_ITEM_MISSING` |
| G6 | 同项结论冲突时整项丢弃 | `E_CONFLICT` |
| G7 | 禁判项的模型结论一律不采用 | `E_FORBIDDEN_ITEM` |

三个关键取舍（都能被追问三层）：

1. **`UNKNOWN` / `NA` 豁免证据与置信度** —— 否则模型诚实地说"我不确定"反而被门禁打死，训练它去编一个 `PASS`。
2. **冲突整项丢弃，不择一** —— 两条都自称有证据、置信度都 0.95 以上时，选哪条都是赌博。正确的动作是交给人。
3. **证据只做格式归一化，不做模糊匹配** —— 放过一条编造的证据，整批结论的可信度归零。宁可漏判，不可误放。

---

## 目录

```
ai-delivery-guard/
├── guard-go/                    Go 实现（纯标准库，无第三方依赖）
│   ├── main.go                  HTTP 服务 + -selfcheck 演示
│   └── guard/
│       ├── model.go             数据契约、原因码
│       ├── engine.go            8 条规则
│       └── engine_test.go       18 条用例
├── guard-java/                  Java 17 实现（纯 JDK，无 Maven）
│   ├── run.bat                  一键编译运行
│   └── src/main/java/com/wsw/guard/
│       ├── Json.java            自写 180 行 JSON parser（为了拿到截断位置）
│       ├── Model.java           record 数据契约
│       ├── Rule.java            原因码常量
│       ├── Engine.java          8 条规则
│       └── Main.java            自测 + demo + HTTP
├── prompts/                     提示词模板库（与规则一一对应）
│   ├── README.md                五段式模板规范、版本策略
│   ├── item-extract.v1.md       检查项结构化抽取
│   ├── license-verify.v1.md     证照有效期核验（含时间锚点事故的防法）
│   └── failure-cases.md         10 类真实失败案例 + 拦截层
└── docs/
    ├── 01-设计说明.md            为什么门禁比"更好的提示词"重要
    ├── 02-双语言实现对照.md       Java↔Go 的 7 个真实差异（含 U+3000 的坑）
    └── 03-三天上手路线.md        自己动手的路线，含"没做过就别说"的红线
```

---

## 当前状态

| 项 | 状态 |
|---|---|
| Go 测试 | 18 用例全过（`go test ./...`） |
| Java 自测 | 17 用例全过（`run.bat`） |
| 跨语言一致性 | demo 输出逐字段一致 |
| 外部依赖 | 0（两边都是纯标准库/JDK） |
| 编译/启动 | 各一条命令，不需要下载任何包 |

---

## 明确不做的事

- **不做自动修正**：门禁只拒绝并说明原因，不猜一个正确的值填上去。任何自动修正都是在脏数据上盖一层更干净的脏数据。
- **不做评测集**：门禁管"能不能用"，评测集管"准不准"。后者需要 100–200 个人工标注的样本，成本高，这个仓库没有做。**这是明确的欠账，不是设计选择。**
- **不做 RAG / 模型路由**：那是另一个问题域，塞进来两边都做不好。
- **不宣称提升准确率**：它提升的是可用数据的可信度，代价是可用数量下降。这两个指标必须一起看。

---

## 背景

这套规则来自一个真实的政务监督检查项目：60+ 项现场检查表、5 类 AI 能力（拍照核验、现场问答、检查项自动回填、语音记录、现场考核），代码 85% 以上由 AI 生成。

那 17 天里踩出来的坑，收敛成了 `prompts/failure-cases.md` 里的 10 类，以及这里的 8 条规则。
