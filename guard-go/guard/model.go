package guard

// Verdict 是模型对单个检查项下的一条结论。
// 四个字段是硬约定：多一个字段不收，少一个字段直接判无效。
// 这是"结构化输出约束"的第一道边界——合同先定死，再谈内容。
type Verdict struct {
	ItemID     string  `json:"itemId"`
	Result     string  `json:"result"`
	Evidence   string  `json:"evidence"`
	Confidence float64 `json:"confidence"`
}

// Request 是一次门禁校验的输入。
type Request struct {
	// FinishReason 必须来自模型响应的真实值。截断检测的第一依据。
	FinishReason string `json:"finishReason"`
	// SourceText 是用户/现场提供的原始文本。证据必须逐字出现在这里面。
	SourceText string `json:"sourceText"`
	// RequestItems 是本次请求的检查项清单。模型不得返回清单外的 itemId。
	RequestItems []string `json:"requestItems"`
	// ForbiddenItems 是禁止模型下结论的检查项（考核类、需人工判定的）。
	ForbiddenItems []string `json:"forbiddenItems"`
	// Verdicts 是模型的原始输出，未经任何清洗。
	Verdicts []Verdict `json:"verdicts"`
}

// Reject 是一条被丢弃的结论及其原因码。
type Reject struct {
	ItemID     string `json:"itemId"`
	ReasonCode string `json:"reasonCode"`
	Detail     string `json:"detail"`
}

// Stats 是本批次的统计，用于监控拦截率。
//
// 口径约定（必须自洽，否则监控看板会骗人）：
//
//	Requested = Accepted + Rejected        // 只统计本次请求的检查项
//	len(Response.Rejected) = Rejected + OutOfScope
//
// OutOfScope 单独计数，是因为"模型编造了一个不存在的检查项"和
// "模型对真实检查项判错了"是两种完全不同的质量事故，合并统计会掩盖前者。
type Stats struct {
	Requested  int `json:"requested"`
	Accepted   int `json:"accepted"`
	Rejected   int `json:"rejected"`
	OutOfScope int `json:"outOfScope"`
}

// Response 是门禁输出。
//
// RejectedAll=true 表示整包不可用（截断、空输出等），此时 Accepted 为空。
// Incomplete=true 表示有请求项没有拿到结论，但其余项仍可用。
// 这两种失败必须分开：前者是技术故障，后者是业务不确定。
// 混在一起的结果就是"任一项判不了 → 整批 422"，可用率归零。
type Response struct {
	OK          bool      `json:"ok"`
	RejectedAll bool      `json:"rejectedAll"`
	Incomplete  bool      `json:"incomplete"`
	ReasonCode  string    `json:"reasonCode,omitempty"`
	Detail      string    `json:"detail,omitempty"`
	Accepted    []Verdict `json:"accepted"`
	Rejected    []Reject  `json:"rejected"`
	Stats       Stats     `json:"stats"`
}

// 原因码。对外暴露的契约，改动等于破坏兼容，只能加不能改。
const (
	CodeTruncated          = "E_TRUNCATED"           // 输出被截断
	CodeItemNotRequested   = "E_ITEM_NOT_REQUESTED"  // 编造或串项的 itemId
	CodeResultNotEnum      = "E_RESULT_NOT_ENUM"     // 结论不是枚举值
	CodeLowConfidence      = "E_LOW_CONFIDENCE"      // 置信度低于阈值
	CodeConfidenceConflict = "E_CONF_CONFLICT"       // 结论与置信度自相矛盾
	CodeEvidenceNotFound   = "E_EVIDENCE_NOT_FOUND"  // 证据在原文中找不到
	CodeEvidenceTooShort   = "E_EVIDENCE_TOO_SHORT"  // 证据过短，不构成证据
	CodeItemMissing        = "E_ITEM_MISSING"        // 请求项没有对应结论
	CodeConflict           = "E_CONFLICT"            // 同项多条结论互相冲突
	CodeForbiddenItem      = "E_FORBIDDEN_ITEM"      // 该项禁止模型下结论
	CodeEmptyOutput        = "E_EMPTY_OUTPUT"        // 模型没返回任何结论
)
