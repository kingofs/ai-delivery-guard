package com.wsw.guard;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * 命令行入口。
 *
 * <pre>
 *   java com.wsw.guard.Main            自测 + 演示（默认）
 *   java com.wsw.guard.Main demo       只跑内置演示
 *   java com.wsw.guard.Main serve      起 HTTP 服务，暴露同样的接口
 *   java com.wsw.guard.Main case.json  校验指定文件
 * </pre>
 */
public final class Main {

    static final String SOURCE = "现场检查记录：该店《食品经营许可证》有效期至 2025 年 5 月 27 日（证号 JY13501020012345）。"
            + "从业人员健康证齐全，均在有效期内。库房内未发现超过保质期的食品原料。";

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "serve".equals(args[0])) {
            serve();
            return;
        }
        if (args.length > 0 && "demo".equals(args[0])) {
            demo();
            return;
        }
        if (args.length > 0) {
            checkFile(args[0]);
            return;
        }
        selfTest();
        System.out.println();
        demo();
    }

    // ---------------- 演示 ----------------

    /** 一份"看起来很正常"的模型输出，实际埋了四类错误。 */
    static Model.Request demoRequest() {
        return req("stop",
                List.of("1.1", "1.2", "1.3", "2.2"),
                List.of("2.2"),
                // 合规项：证据能在原文里逐字找到
                new Model.Verdict("1.1", "PASS", "有效期至 2025 年 5 月 27 日", 0.96),
                // 低置信度：模型自己都不确定，却写成了 PASS
                new Model.Verdict("1.2", "PASS", "健康证齐全", 0.71),
                // 证据造假：原文里没有这句话
                new Model.Verdict("1.3", "PASS", "库房温度符合冷藏要求", 0.94),
                // 越权：该项属人工判定，模型不该给结论
                new Model.Verdict("2.2", "FAIL", "未发现超过保质期的食品原料", 0.93),
                // 编造：请求里没有 9.9 这一项
                new Model.Verdict("9.9", "PASS", "从业人员健康证齐全", 0.99));
    }

    static void demo() {
        Model.Response resp = new Engine().check(demoRequest());
        System.out.println(resp.toJson());
        System.out.printf("%n请求 %d 项 → 可用 %d 项，业务丢弃 %d 项，清单外编造 %d 项（整包拒绝=%s，有缺项=%s）%n",
                resp.stats().requested(), resp.stats().accepted(), resp.stats().rejected(),
                resp.stats().outOfScope(), resp.rejectedAll(), resp.incomplete());
        if (!resp.stats().consistent()) {
            throw new IllegalStateException("统计口径不自洽: " + resp.stats());
        }
    }

    // ---------------- 自测 ----------------

    record Expect(int accepted, int rejected, boolean all, boolean incomplete, String code) {
    }

    static void selfTest() {
        List<Object[]> cases = List.of(
                new Object[]{"全部合规时全量通过", base(),
                        new Expect(3, 0, false, false, null)},
                new Object[]{"输出被截断时整包拒绝", req("length", List.of("1.1", "1.2", "2.2"), List.of(), ok3()),
                        new Expect(0, 0, true, false, Rule.TRUNCATED)},
                new Object[]{"没给 finish_reason 时按截断处理", req("", List.of("1.1", "1.2", "2.2"), List.of(), ok3()),
                        new Expect(0, 0, true, false, Rule.TRUNCATED)},
                new Object[]{"模型没返回任何结论", req("stop", List.of("1.1"), List.of(), new Model.Verdict[0]),
                        new Expect(0, 0, true, false, Rule.EMPTY_OUTPUT)},
                new Object[]{"编造清单外的检查项要被点名",
                        req("stop", List.of("1.1", "1.2", "2.2"), List.of(), append(ok3(),
                                new Model.Verdict("9.9", "PASS", "有效期内", 0.99))),
                        new Expect(3, 1, false, false, Rule.ITEM_NOT_REQUESTED)},
                new Object[]{"自由文本结论要被拦下",
                        req("stop", List.of("1.1", "1.2", "2.2"), List.of(), replaceFirst(ok3(),
                                new Model.Verdict("1.1", "基本合格", "有效期内", 0.97))),
                        new Expect(2, 1, false, false, Rule.RESULT_NOT_ENUM)},
                new Object[]{"低置信度结论不下发",
                        req("stop", List.of("1.1", "1.2", "2.2"), List.of(), replaceFirst(ok3(),
                                new Model.Verdict("1.1", "PASS", "有效期内", 0.72))),
                        new Expect(2, 1, false, false, Rule.LOW_CONFIDENCE)},
                new Object[]{"没传置信度不按 1.0 处理",
                        req("stop", List.of("1.1", "1.2", "2.2"), List.of(), replaceFirst(ok3(),
                                new Model.Verdict("1.1", "PASS", "有效期内", 0.0))),
                        new Expect(2, 1, false, false, Rule.LOW_CONFIDENCE)},
                new Object[]{"UNKNOWN 不要求证据也不要求置信度",
                        req("stop", List.of("1.1", "1.2", "2.2"), List.of(), replaceFirst(ok3(),
                                new Model.Verdict("1.1", "UNKNOWN", "", 0.10))),
                        new Expect(3, 0, false, false, null)},
                new Object[]{"极度自信地说不知道属于自相矛盾",
                        req("stop", List.of("1.1", "1.2", "2.2"), List.of(), replaceFirst(ok3(),
                                new Model.Verdict("1.1", "UNKNOWN", "", 0.99))),
                        new Expect(2, 1, false, false, Rule.CONF_CONFLICT)},
                new Object[]{"证据是编造的要判死",
                        req("stop", List.of("1.1", "1.2", "2.2"), List.of(), replaceFirst(ok3(),
                                new Model.Verdict("1.1", "PASS", "许可证有效期至 2027 年 12 月 31 日", 0.96))),
                        new Expect(2, 1, false, false, Rule.EVIDENCE_NOT_FOUND)},
                new Object[]{"证据太短不构成证据",
                        req("stop", List.of("1.1", "1.2", "2.2"), List.of(), replaceFirst(ok3(),
                                new Model.Verdict("1.1", "PASS", "是", 0.99))),
                        new Expect(2, 1, false, false, Rule.EVIDENCE_TOO_SHORT)},
                new Object[]{"全角空格与全角标点归一化后仍算命中",
                        req("stop", List.of("1.1", "1.2", "2.2"), List.of(), replaceFirst(ok3(),
                                new Model.Verdict("1.1", "PASS", "（证号　JY13501020012345）", 0.99))),
                        new Expect(3, 0, false, false, null)},
                new Object[]{"缺项要报出来，不能补一个合理缺项",
                        req("stop", List.of("1.1", "1.2", "2.2"), List.of(),
                                new Model.Verdict[]{ok3()[0], ok3()[1]}),
                        new Expect(2, 1, false, true, Rule.ITEM_MISSING)},
                new Object[]{"同项两条冲突结论整项丢弃",
                        req("stop", List.of("1.1", "1.2", "2.2"), List.of(), append(ok3(),
                                new Model.Verdict("1.1", "FAIL", "有效期内", 0.96))),
                        new Expect(2, 1, false, false, Rule.CONFLICT)},
                new Object[]{"同项重复但结论一致时只保留一条",
                        req("stop", List.of("1.1", "1.2", "2.2"), List.of(), append(ok3(),
                                new Model.Verdict("1.1", "PASS", "有效期内", 0.98))),
                        new Expect(3, 0, false, false, null)},
                new Object[]{"禁止模型下结论的项一律不采用",
                        req("stop", List.of("1.1", "1.2", "2.2"), List.of("2.2"), ok3()),
                        new Expect(2, 1, false, false, Rule.FORBIDDEN_ITEM)}
        );

        Engine engine = new Engine();
        int pass = 0;
        List<String> failures = new ArrayList<>();
        for (Object[] c : cases) {
            String name = (String) c[0];
            Model.Request req = (Model.Request) c[1];
            Expect exp = (Expect) c[2];
            Model.Response got = engine.check(req);

            String err = null;
            if (got.rejectedAll() != exp.all()) {
                err = "rejectedAll=" + got.rejectedAll() + ", want " + exp.all();
            } else if (got.incomplete() != exp.incomplete()) {
                err = "incomplete=" + got.incomplete() + ", want " + exp.incomplete();
            } else if (got.accepted().size() != exp.accepted()) {
                err = "accepted=" + got.accepted().size() + ", want " + exp.accepted();
            } else if (got.rejected().size() != exp.rejected()) {
                err = "rejected=" + got.rejected().size() + ", want " + exp.rejected() + " => " + got.rejected();
            } else if (exp.code() != null) {
                boolean found = exp.code().equals(got.reasonCode());
                for (Model.Reject r : got.rejected()) {
                    if (exp.code().equals(r.reasonCode())) {
                        found = true;
                    }
                }
                if (!found) {
                    err = "expected code " + exp.code() + " => " + got.rejected();
                }
            }

            if (err == null) {
                pass++;
                System.out.println("  [PASS] " + name);
            } else {
                failures.add(name + " :: " + err);
                System.out.println("  [FAIL] " + name + " :: " + err);
            }
        }
        System.out.printf("%n自测结果：%d/%d 通过%n", pass, cases.size());
        if (!failures.isEmpty()) {
            failures.forEach(f -> System.out.println("    " + f));
            System.exit(1);
        }
    }

    // ---------------- 服务 ----------------

    static void serve() throws IOException {
        Engine engine = new Engine();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 8091), 0);

        server.createContext("/health", ex -> respond(ex, 200, "{\"status\":\"UP\"}"));

        // 与 Go 版保持同一契约：POST 原始 JSON 进，Response JSON 出。
        server.createContext("/guard/check", ex -> {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                respond(ex, 405, "{\"error\":\"only POST\"}");
                return;
            }
            Model.Response resp;
            try (InputStream in = ex.getRequestBody()) {
                String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                resp = engine.check(Model.Request.fromJson(body));
            } catch (RuntimeException e) {
                // 连 JSON 都解析不了属于技术故障，走整包拒绝语义，不是 500。
                resp = Model.Response.rejectedAll(Rule.EMPTY_OUTPUT,
                        "请求体不是合法 JSON：" + e.getMessage(), 0);
            }
            respond(ex, 200, resp.toJson());
        });

        server.createContext("/demo", ex -> respond(ex, 200, engine.check(demoRequest()).toJson()));

        // HttpServer 默认的 executor 是 null，含义是"由实现自己决定"，实际表现是串行处理。
        // 这一点和 Go 的 net/http（每请求一个 goroutine）完全不同，必须显式给线程池，
        // 否则门禁会成为整条链路的串行瓶颈。
        server.setExecutor(Executors.newFixedThreadPool(
                Math.max(4, Runtime.getRuntime().availableProcessors())));

        server.start();
        System.out.println("ai-delivery-guard (java) listening on http://127.0.0.1:8091  (/health /demo POST /guard/check)");
    }

    static void respond(com.sun.net.httpserver.HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(b);
        }
    }

    static void checkFile(String path) throws IOException {
        String text = Files.readString(Path.of(path), StandardCharsets.UTF_8);
        Model.Response resp = new Engine().check(Model.Request.fromJson(text));
        System.out.println(resp.toJson());
    }

    // ---------------- 构造辅助 ----------------

    static Model.Request req(String finishReason, List<String> items, List<String> forbidden,
                             Model.Verdict... verdicts) {
        return new Model.Request(finishReason, SOURCE, items, forbidden, List.of(verdicts));
    }

    static Model.Request base() {
        return req("stop", List.of("1.1", "1.2", "2.2"), List.of(), ok3());
    }

    static Model.Verdict[] ok3() {
        return new Model.Verdict[]{
                new Model.Verdict("1.1", "PASS", "有效期内", 0.97),
                new Model.Verdict("1.2", "PASS", "健康证齐全，均在有效期内", 0.93),
                new Model.Verdict("2.2", "UNKNOWN", "", 0.40)
        };
    }

    static Model.Verdict[] append(Model.Verdict[] src, Model.Verdict extra) {
        Model.Verdict[] out = new Model.Verdict[src.length + 1];
        System.arraycopy(src, 0, out, 0, src.length);
        out[src.length] = extra;
        return out;
    }

    static Model.Verdict[] replaceFirst(Model.Verdict[] src, Model.Verdict first) {
        Model.Verdict[] out = src.clone();
        out[0] = first;
        return out;
    }
}
