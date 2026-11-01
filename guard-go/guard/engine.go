package guard

import (
	"strconv"
	"strings"
	"unicode"
)

func itoa(n int) string { return strconv.Itoa(n) }

// 默认阈值。写成常量而不是配置项，是因为这两个数字背后是业务后果：
//   MinConfidence 0.90 —— 低于它的合规结论，宁可让人补，不可直接落库。
//   MinEvidenceLen 4   —— 少于 4 个字的"证据"（"是"、"无"、"通过"）不构成证据。
// 允许外部覆盖，但默认值必须保守。
const (
	DefaultMinConfidence  = 0.90
	DefaultMinEvidenceLen = 4
)

// 结论枚举。这是契约的一部分，字段值只允许这四个。
var allowedResults = map[string]bool{
	"PASS":    true,
	"FAIL":    true,
	"UNKNOWN": true,
	"NA":      true,
}

// Engine 是校验门禁本体。无状态，可并发复用。
type Engine struct {
	MinConfidence  float64
	MinEvidenceLen int
}

// New 用默认阈值构造引擎。
func New() *Engine {
	return &Engine{
		MinConfidence:  DefaultMinConfidence,
		MinEvidenceLen: DefaultMinEvidenceLen,
	}
}

// Check 执行全部门禁规则，返回可用项与丢弃项。
//
// 设计原则（三条，顺序不能反）：
//  1. 技术故障整包拒绝：截断、空输出。这类输出继续往下走只会制造脏数据。
//  2. 业务不确定逐项拒绝：缺项、证据不足、置信度低。其余项照常交付。
//  3. 冲突整项丢弃，不取"最新一条"：AI 重复输出且互相矛盾时，选哪条都是赌博。
func (e *Engine) Check(req *Request) Response {
	resp := Response{Accepted: []Verdict{}, Rejected: []Reject{}}
	if req == nil {
		resp.ReasonCode = CodeEmptyOutput
		resp.Detail = "请求为空"
		resp.RejectedAll = true
		return resp
	}
	resp.Stats.Requested = len(req.RequestItems)

	// G0：截断检测。finish_reason 不是 stop，或者干脆没传，一律整包拒绝。
	// 不看 finish_reason 只看 JSON 能否 parse，是"被 max_tokens 截断但仍合法"这类事故的根源。
	if req.FinishReason != "stop" {
		resp.RejectedAll = true
		resp.ReasonCode = CodeTruncated
		resp.Detail = "finish_reason=" + quoteOrEmpty(req.FinishReason) + "，非 stop，输出可能被截断，整包不采用"
		return resp
	}

	if len(req.Verdicts) == 0 {
		resp.RejectedAll = true
		resp.ReasonCode = CodeEmptyOutput
		resp.Detail = "模型未返回任何结论"
		return resp
	}

	// 按 itemId 分组，保持首次出现的顺序。
	grouped := make(map[string][]Verdict)
	order := make([]string, 0, len(req.Verdicts))
	for _, v := range req.Verdicts {
		id := strings.TrimSpace(v.ItemID)
		if _, ok := grouped[id]; !ok {
			order = append(order, id)
		}
		v.ItemID = id
		grouped[id] = append(grouped[id], v)
	}

	requested := make(map[string]bool, len(req.RequestItems))
	forbidden := make(map[string]bool, len(req.ForbiddenItems))
	for _, id := range req.RequestItems {
		requested[strings.TrimSpace(id)] = true
	}
	for _, id := range req.ForbiddenItems {
		forbidden[strings.TrimSpace(id)] = true
	}

	// 按请求顺序逐项判定，保证输出稳定、可对比。
	for _, rawID := range req.RequestItems {
		id := strings.TrimSpace(rawID)

		items := grouped[id]
		if len(items) == 0 {
			// G5：缺项。不补一个"合理缺项"，明确报出来。
			resp.Incomplete = true
			resp.Rejected = append(resp.Rejected, Reject{
				ItemID:     id,
				ReasonCode: CodeItemMissing,
				Detail:     "请求项未返回结论，不采用推测填充",
			})
			continue
		}

		// G7：业务禁判项。模型连结论都不该给。
		if forbidden[id] {
			resp.Rejected = append(resp.Rejected, Reject{
				ItemID:     id,
				ReasonCode: CodeForbiddenItem,
				Detail:     "该项属人工判定范围，模型结论不予采用",
			})
			continue
		}

		// G6：同项多条结论。
		if len(items) > 1 {
			first := items[0].Result
			conflict := false
			for _, it := range items[1:] {
				if !strings.EqualFold(strings.TrimSpace(it.Result), strings.TrimSpace(first)) {
					conflict = true
					break
				}
			}
			if conflict {
				resp.Rejected = append(resp.Rejected, Reject{
					ItemID:     id,
					ReasonCode: CodeConflict,
					Detail:     "同项返回 " + itoa(len(items)) + " 条互相冲突的结论，整项丢弃而非择一采用",
				})
				continue
			}
			// 结论一致，保留第一条；后几条视为重复输出。
			items = items[:1]
		}

		// 项级规则 G1–G4。
		if rej := e.checkOne(id, items[0], req.SourceText); rej != nil {
			resp.Rejected = append(resp.Rejected, *rej)
			continue
		}
		resp.Accepted = append(resp.Accepted, items[0])
	}

	// G1（反向）：返回了请求清单外的 itemId —— 编造或串项。
	for _, id := range order {
		if requested[id] {
			continue
		}
		resp.Stats.OutOfScope++
		resp.Rejected = append(resp.Rejected, Reject{
			ItemID:     id,
			ReasonCode: CodeItemNotRequested,
			Detail:     "返回了本次请求清单外的检查项，视为编造或串项",
		})
	}

	resp.Stats.Accepted = len(resp.Accepted)
	resp.Stats.Rejected = len(resp.Rejected) - resp.Stats.OutOfScope
	resp.OK = len(resp.Accepted) > 0 && !resp.RejectedAll
	return resp
}

// checkOne 校验单条结论，通过返回 nil。
func (e *Engine) checkOne(itemID string, v Verdict, source string) *Reject {
	result := strings.ToUpper(strings.TrimSpace(v.Result))

	// G2：枚举校验。AI 最爱输出"基本合格""存疑"这类自由文本。
	if !allowedResults[result] {
		return &Reject{ItemID: itemID, ReasonCode: CodeResultNotEnum,
			Detail: "result=" + quoteOrEmpty(v.Result) + " 不在枚举 [PASS FAIL UNKNOWN NA] 内"}
	}

	// UNKNOWN / NA 是"判不了"和"不适用"，不要求证据、不要求置信度。
	// 这一条必须放行：否则模型诚实地说"我不确定"，反而被门禁打死。
	if result == "UNKNOWN" || result == "NA" {
		// 但极高置信度 + UNKNOWN 是自我矛盾，典型的模型措辞漂移。
		if v.Confidence >= 0.95 {
			return &Reject{ItemID: itemID, ReasonCode: CodeConfidenceConflict,
				Detail: "结论为 UNKNOWN 但置信度 0.95 以上，输出自相矛盾"}
		}
		return nil
	}

	// G3：置信度。缺省 0 不视为 1.0，直接判低。
	if v.Confidence < 0 || v.Confidence > 1 {
		return &Reject{ItemID: itemID, ReasonCode: CodeLowConfidence,
			Detail: "confidence 越界 [0,1]，视为无效"}
	}
	if v.Confidence < e.MinConfidence {
		return &Reject{ItemID: itemID, ReasonCode: CodeLowConfidence,
			Detail: "confidence 低于阈值，结论不下发，转人工补充"}
	}

	// G4：证据逐字校验。
	evidence := Normalize(v.Evidence)
	if len([]rune(evidence)) < e.MinEvidenceLen {
		return &Reject{ItemID: itemID, ReasonCode: CodeEvidenceTooShort,
			Detail: "证据长度不足 " + itoa(e.MinEvidenceLen) + " 字，不构成有效证据"}
	}
	if !strings.Contains(Normalize(source), evidence) {
		return &Reject{ItemID: itemID, ReasonCode: CodeEvidenceNotFound,
			Detail: "证据在原始文本中不存在（逐字匹配失败），判定为模型编造"}
	}
	return nil
}

// Normalize 归一化文本用于逐字比对。
//
// 归一化只做"格式层"的容忍，不做"语义层"的模糊：
//   - 去掉所有空白（含全角空格、换行、制表符）
//   - 全角 ASCII（！到 ～）转半角
//   - 统一小写
//
// 故意不做的事：不做同义词、不做编辑距离、不做部分匹配。
// 因为这里只要放过一条编造的证据，整批结论的可信度就归零。
// 宁可比对失败（漏判），也不能让编造的证据通过（误放）。
func Normalize(s string) string {
	var b strings.Builder
	b.Grow(len(s))
	for _, r := range s {
		switch {
		case unicode.IsSpace(r):
			continue
		case r >= 0xFF01 && r <= 0xFF5E:
			b.WriteRune(r - 0xFEE0)
		default:
			b.WriteRune(r)
		}
	}
	return strings.ToLower(b.String())
}

func quoteOrEmpty(s string) string {
	if strings.TrimSpace(s) == "" {
		return "(空)"
	}
	return s
}
