package guard

import (
	"testing"
)

const demoSource = "现场检查记录：该店《食品经营许可证》有效期至 2025 年 5 月 27 日（证号 JY13501020012345）。从业人员健康证齐全，均在有效期内。"

func baseReq() *Request {
	return &Request{
		FinishReason: "stop",
		SourceText:   demoSource,
		RequestItems: []string{"1.1", "1.2", "2.2"},
		Verdicts: []Verdict{
			{ItemID: "1.1", Result: "PASS", Evidence: "有效期内", Confidence: 0.97},
			{ItemID: "1.2", Result: "PASS", Evidence: "健康证齐全，均在有效期内", Confidence: 0.93},
			{ItemID: "2.2", Result: "UNKNOWN", Evidence: "", Confidence: 0.40},
		},
	}
}

// 门禁的正确行为必须能被测试逐条钉死。
// 每条用例的注释写的是"这条规则防的是什么事故"，不是"这条代码做什么"。
func TestCheck(t *testing.T) {
	type want struct {
		rejectedAll bool
		incomplete  bool
		accepted    int
		rejected    int
		code        string // 期望出现在 rejected 里的原因码；空表示不校验
	}

	cases := []struct {
		name   string
		mutate func(r *Request)
		want   want
	}{
		{
			name:   "全部合规时全量通过",
			mutate: func(r *Request) {},
			want:   want{accepted: 3, rejected: 0},
		},
		{
			name:   "输出被截断时整包拒绝",
			mutate: func(r *Request) { r.FinishReason = "length" },
			want:   want{rejectedAll: true, code: CodeTruncated},
		},
		{
			name:   "调用方没给 finish_reason 时按截断处理",
			mutate: func(r *Request) { r.FinishReason = "" },
			want:   want{rejectedAll: true, code: CodeTruncated},
		},
		{
			name:   "模型没返回任何结论时整包拒绝",
			mutate: func(r *Request) { r.Verdicts = nil },
			want:   want{rejectedAll: true, code: CodeEmptyOutput},
		},
		{
			name: "编造请求清单外的检查项要被点名",
			mutate: func(r *Request) {
				r.Verdicts = append(r.Verdicts, Verdict{ItemID: "9.9", Result: "PASS", Evidence: "有效期内", Confidence: 0.99})
			},
			want: want{accepted: 3, rejected: 1, code: CodeItemNotRequested},
		},
		{
			name: "自由文本结论要被拦下",
			mutate: func(r *Request) {
				r.Verdicts[0].Result = "基本合格"
			},
			want: want{accepted: 2, rejected: 1, code: CodeResultNotEnum},
		},
		{
			name: "低置信度结论不下发",
			mutate: func(r *Request) {
				r.Verdicts[0].Confidence = 0.72
			},
			want: want{accepted: 2, rejected: 1, code: CodeLowConfidence},
		},
		{
			name: "没传置信度不按 1.0 处理",
			mutate: func(r *Request) {
				r.Verdicts[0].Confidence = 0
			},
			want: want{accepted: 2, rejected: 1, code: CodeLowConfidence},
		},
		{
			name: "UNKNOWN 不要求证据也不要求置信度",
			mutate: func(r *Request) {
				r.Verdicts[0] = Verdict{ItemID: "1.1", Result: "UNKNOWN", Confidence: 0.1}
			},
			want: want{accepted: 3, rejected: 0},
		},
		{
			name: "NA 放行",
			mutate: func(r *Request) {
				r.Verdicts[0] = Verdict{ItemID: "1.1", Result: "NA", Confidence: 0}
			},
			want: want{accepted: 3, rejected: 0},
		},
		{
			name: "极度自信地说不知道，属于自相矛盾",
			mutate: func(r *Request) {
				r.Verdicts[0] = Verdict{ItemID: "1.1", Result: "UNKNOWN", Confidence: 0.99}
			},
			want: want{accepted: 2, rejected: 1, code: CodeConfidenceConflict},
		},
		{
			name: "证据是编造的要判死",
			mutate: func(r *Request) {
				r.Verdicts[0].Evidence = "许可证有效期至 2027 年 12 月 31 日"
			},
			want: want{accepted: 2, rejected: 1, code: CodeEvidenceNotFound},
		},
		{
			name: "证据太短不构成证据",
			mutate: func(r *Request) {
				r.Verdicts[0].Evidence = "是"
			},
			want: want{accepted: 2, rejected: 1, code: CodeEvidenceTooShort},
		},
		{
			name: "全角空格与全角标点归一化后仍算命中",
			mutate: func(r *Request) {
				r.Verdicts[0].Evidence = "（证号　JY13501020012345）"
			},
			want: want{accepted: 3, rejected: 0},
		},
		{
			name: "缺项要报出来，不能补一个合理缺项",
			mutate: func(r *Request) {
				r.Verdicts = r.Verdicts[:2]
			},
			want: want{incomplete: true, accepted: 2, rejected: 1, code: CodeItemMissing},
		},
		{
			name: "同项两条冲突结论整项丢弃",
			mutate: func(r *Request) {
				r.Verdicts = append(r.Verdicts, Verdict{ItemID: "1.1", Result: "FAIL", Evidence: "有效期内", Confidence: 0.96})
			},
			want: want{accepted: 2, rejected: 1, code: CodeConflict},
		},
		{
			name: "同项重复但结论一致时只保留一条",
			mutate: func(r *Request) {
				r.Verdicts = append(r.Verdicts, Verdict{ItemID: "1.1", Result: "PASS", Evidence: "有效期内", Confidence: 0.98})
			},
			want: want{accepted: 3, rejected: 0},
		},
		{
			name: "禁止模型下结论的项一律不采用",
			mutate: func(r *Request) {
				r.ForbiddenItems = []string{"2.2"}
			},
			want: want{accepted: 2, rejected: 1, code: CodeForbiddenItem},
		},
	}

	e := New()
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			req := baseReq()
			c.mutate(req)
			got := e.Check(req)

			if got.RejectedAll != c.want.rejectedAll {
				t.Fatalf("rejectedAll = %v, want %v", got.RejectedAll, c.want.rejectedAll)
			}
			if got.Incomplete != c.want.incomplete {
				t.Fatalf("incomplete = %v, want %v", got.Incomplete, c.want.incomplete)
			}
			if len(got.Accepted) != c.want.accepted {
				t.Fatalf("accepted = %d, want %d; rejected=%+v", len(got.Accepted), c.want.accepted, got.Rejected)
			}
			if len(got.Rejected) != c.want.rejected {
				t.Fatalf("rejected = %d, want %d; rejected=%+v", len(got.Rejected), c.want.rejected, got.Rejected)
			}
			if c.want.code != "" {
				found := got.ReasonCode == c.want.code
				for _, r := range got.Rejected {
					if r.ReasonCode == c.want.code {
						found = true
					}
				}
				if !found {
					t.Fatalf("expected reason code %s, got %+v", c.want.code, got)
				}
			}
		})
	}
}

// 统计口径必须自洽：请求项数 = 可用数 + 丢弃的业务项数（不含清单外项）。
func TestStatsConsistency(t *testing.T) {
	req := baseReq()
	req.Verdicts[1].Confidence = 0.5
	resp := New().Check(req)
	if resp.Stats.Accepted != len(resp.Accepted) {
		t.Fatalf("stats.accepted 与实际不一致")
	}
	if resp.Stats.Requested != 3 {
		t.Fatalf("stats.requested 应等于请求项数")
	}
	if resp.Stats.Requested != resp.Stats.Accepted+resp.Stats.Rejected {
		t.Fatalf("口径不自洽: requested=%d accepted=%d rejected=%d",
			resp.Stats.Requested, resp.Stats.Accepted, resp.Stats.Rejected)
	}
	if resp.Stats.OutOfScope != 0 {
		t.Fatalf("没有清单外项时 outOfScope 应为 0")
	}
}

// 清单外项单独计数，不能混进业务丢弃数里。
func TestOutOfScopeCountedSeparately(t *testing.T) {
	req := baseReq()
	req.Verdicts = append(req.Verdicts,
		Verdict{ItemID: "9.9", Result: "PASS", Evidence: "有效期内", Confidence: 0.99})
	resp := New().Check(req)
	if resp.Stats.OutOfScope != 1 {
		t.Fatalf("outOfScope = %d, want 1", resp.Stats.OutOfScope)
	}
	if resp.Stats.Requested != resp.Stats.Accepted+resp.Stats.Rejected {
		t.Fatalf("口径不自洽: %+v", resp.Stats)
	}
	if len(resp.Rejected) != resp.Stats.Rejected+resp.Stats.OutOfScope {
		t.Fatalf("rejected 列表长度与口径不符: %d vs %+v", len(resp.Rejected), resp.Stats)
	}
}

func TestCheckNil(t *testing.T) {
	resp := New().Check(nil)
	if !resp.RejectedAll || resp.ReasonCode != CodeEmptyOutput {
		t.Fatalf("nil 请求应整包拒绝，got %+v", resp)
	}
}

func TestNormalize(t *testing.T) {
	cases := map[string]string{
		"  A B\tC\n":     "abc",
		"（全角）":          "(全角)",
		"ＪＹ１２３":         "jy123",
		"中文　空格":         "中文空格",
	}
	for in, want := range cases {
		if got := Normalize(in); got != want {
			t.Fatalf("Normalize(%q) = %q, want %q", in, got, want)
		}
	}
}
