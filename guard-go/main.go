// ai-delivery-guard / guard-go
//
// 一个把"AI 输出质量门禁"做成可执行代码的最小服务。
// 纯标准库，无框架、无第三方依赖——刻意的：面试时 `go run .` 就起，不用等 go mod download。
package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"time"

	"github.com/kingofs/ai-delivery-guard/guard-go/guard"
)

const demoSourceText = "现场检查记录：该店《食品经营许可证》有效期至 2025 年 5 月 27 日（证号 JY13501020012345）。" +
	"从业人员健康证齐全，均在有效期内。库房内未发现超过保质期的食品原料。"

// demoRequest 是一份"看起来很正常"的模型输出，实际埋了四类错误。
// 它比任何文档都更能说明这个项目在防什么。
func demoRequest() *guard.Request {
	return &guard.Request{
		FinishReason:   "stop",
		SourceText:     demoSourceText,
		RequestItems:   []string{"1.1", "1.2", "1.3", "2.2"},
		ForbiddenItems: []string{"2.2"},
		Verdicts: []guard.Verdict{
			// 合规项：证据在原文里能逐字找到。
			{ItemID: "1.1", Result: "PASS", Evidence: "有效期至 2025 年 5 月 27 日", Confidence: 0.96},
			// 低置信度：模型自己也不确定，却被写成了 PASS。
			// {ItemID: "1.2", Result: "PASS", Evidence: "健康证齐全", Confidence: 0.71},
			{ItemID: "1.2", Result: "PASS", Evidence: "健康证齐全", Confidence: 0.95},
			// 证据造假：原文里根本没有这句话。
			{ItemID: "1.3", Result: "PASS", Evidence: "库房温度符合冷藏要求", Confidence: 0.94},
			// 越权：该项属人工判定，模型不该给结论。
			{ItemID: "2.2", Result: "FAIL", Evidence: "未发现超过保质期的食品原料", Confidence: 0.93},
			// 编造：请求里没有 9.9 这一项。
			{ItemID: "9.9", Result: "PASS", Evidence: "从业人员健康证齐全", Confidence: 0.99},
		},
	}
}

func main() {
	addr := flag.String("addr", ":8090", "监听地址")
	selfCheck := flag.Bool("selfcheck", false, "跑一遍内置示例并退出，不启服务")
	flag.Parse()

	engine := guard.New()

	if *selfCheck {
		printResult(engine.Check(demoRequest()))
		return
	}

	mux := http.NewServeMux()

	mux.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, http.StatusOK, map[string]any{"status": "UP", "ts": time.Now().Format(time.RFC3339)})
	})

	// POST /guard/check
	// 请求体是 guard.Request，响应是 guard.Response。
	// 这就是"AI 输出 → 门禁 → 可用结论"的完整闭环，前端/后端任何一层都能调。
	mux.HandleFunc("/guard/check", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			writeJSON(w, http.StatusMethodNotAllowed, map[string]string{"error": "only POST"})
			return
		}
		body, err := io.ReadAll(io.LimitReader(r.Body, 1<<20))
		if err != nil {
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": "read body failed"})
			return
		}
		var req guard.Request
		if err := json.Unmarshal(body, &req); err != nil {
			// 连 JSON 都解析不了，属于技术故障，走整包拒绝语义，不是 500。
			writeJSON(w, http.StatusOK, guard.Response{
				RejectedAll: true,
				ReasonCode:  guard.CodeEmptyOutput,
				Detail:      "请求体不是合法 JSON：" + err.Error(),
			})
			return
		}
		writeJSON(w, http.StatusOK, engine.Check(&req))
	})

	// GET /demo 浏览器里直接看效果，面试演示不用装 curl。
	mux.HandleFunc("/demo", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, http.StatusOK, engine.Check(demoRequest()))
	})

	log.Printf("ai-delivery-guard listening on %s  (/health /demo POST /guard/check)", *addr)
	srv := &http.Server{
		Addr:              *addr,
		Handler:           mux,
		ReadHeaderTimeout: 5 * time.Second,
	}
	if err := srv.ListenAndServe(); err != nil {
		log.Fatalf("listen failed: %v", err)
		os.Exit(1)
	}
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	enc := json.NewEncoder(w)
	enc.SetEscapeHTML(false)
	enc.SetIndent("", "  ")
	_ = enc.Encode(v)
}

func printResult(resp guard.Response) {
	b, _ := json.MarshalIndent(resp, "", "  ")
	fmt.Println(string(b))
	fmt.Println()
	fmt.Printf("请求 %d 项 → 可用 %d 项，业务丢弃 %d 项，清单外编造 %d 项（整包拒绝=%v，有缺项=%v）\n",
		resp.Stats.Requested, resp.Stats.Accepted, resp.Stats.Rejected,
		resp.Stats.OutOfScope, resp.RejectedAll, resp.Incomplete)
}
