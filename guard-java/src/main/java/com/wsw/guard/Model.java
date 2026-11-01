package com.wsw.guard;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 门禁的数据契约。
 *
 * <p>用 record 而不是 POJO，是刻意的：这些对象一旦构造就不该再被修改。
 * 校验结论在流转过程中被任何一层"顺手改一下"，门禁就失去意义了。
 */
public final class Model {

    private Model() {
    }

    /** 模型对单个检查项的一条结论。四字段硬约定，多不行少不行。 */
    public record Verdict(String itemId, String result, String evidence, double confidence) {
    }

    /** 被丢弃的结论及其原因码。 */
    public record Reject(String itemId, String reasonCode, String detail) {
    }

    /**
     * 统计。
     *
     * <p>口径：{@code requested == accepted + rejected}；{@code rejected 列表长度 == rejected + outOfScope}。
     * outOfScope 单独计数，因为"模型编造了一个不存在的检查项"和"模型对真实项判错了"
     * 是两种不同的质量事故，混在一起统计会掩盖前者。
     */
    public record Stats(int requested, int accepted, int rejected, int outOfScope) {
        public boolean consistent() {
            return requested == accepted + rejected;
        }
    }

    /** 一次门禁校验的输入。 */
    public record Request(String finishReason, String sourceText, List<String> requestItems,
                          List<String> forbiddenItems, List<Verdict> verdicts) {

        public Request {
            requestItems = requestItems == null ? List.of() : requestItems;
            forbiddenItems = forbiddenItems == null ? List.of() : forbiddenItems;
            verdicts = verdicts == null ? List.of() : verdicts;
        }

        /** 从 JSON 文本解析。调用层拿到的模型原始输出，不做任何清洗直接进来。 */
        public static Request fromJson(String text) {
            Object root = Json.parse(text);
            if (!(root instanceof Map<?, ?> m)) {
                throw new Json.JsonException("请求体必须是 JSON 对象");
            }
            List<Verdict> vs = new ArrayList<>();
            Object raw = m.get("verdicts");
            if (raw instanceof List<?> list) {
                for (Object o : list) {
                    if (!(o instanceof Map<?, ?> vm)) {
                        throw new Json.JsonException("verdicts 的元素必须是对象");
                    }
                    vs.add(new Verdict(
                            asString(vm.get("itemId")),
                            asString(vm.get("result")),
                            asString(vm.get("evidence")),
                            asDouble(vm.get("confidence"))));
                }
            }
            return new Request(
                    asString(m.get("finishReason")),
                    asString(m.get("sourceText")),
                    asStringList(m.get("requestItems")),
                    asStringList(m.get("forbiddenItems")),
                    vs);
        }
    }

    /** 门禁输出。 */
    public record Response(boolean ok, boolean rejectedAll, boolean incomplete,
                           String reasonCode, String detail,
                           List<Verdict> accepted, List<Reject> rejected, Stats stats) {

        public static Response rejectedAll(String code, String detail, int requested) {
            return new Response(false, true, false, code, detail,
                    List.of(), List.of(), new Stats(requested, 0, 0, 0));
        }

        public String toJson() {
            StringBuilder sb = new StringBuilder(512);
            sb.append("{\n");
            sb.append("  \"ok\": ").append(ok).append(",\n");
            sb.append("  \"rejectedAll\": ").append(rejectedAll).append(",\n");
            sb.append("  \"incomplete\": ").append(incomplete).append(",\n");
            if (reasonCode != null) {
                sb.append("  \"reasonCode\": \"").append(Json.escape(reasonCode)).append("\",\n");
            }
            if (detail != null) {
                sb.append("  \"detail\": \"").append(Json.escape(detail)).append("\",\n");
            }

            sb.append("  \"accepted\": [");
            for (int i = 0; i < accepted.size(); i++) {
                Verdict v = accepted.get(i);
                sb.append(i == 0 ? "\n" : ",\n");
                sb.append("    {\"itemId\": \"").append(Json.escape(v.itemId()))
                        .append("\", \"result\": \"").append(Json.escape(v.result()))
                        .append("\", \"evidence\": \"").append(Json.escape(v.evidence()))
                        .append("\", \"confidence\": ").append(v.confidence()).append("}");
            }
            sb.append(accepted.isEmpty() ? "],\n" : "\n  ],\n");

            sb.append("  \"rejected\": [");
            for (int i = 0; i < rejected.size(); i++) {
                Reject r = rejected.get(i);
                sb.append(i == 0 ? "\n" : ",\n");
                sb.append("    {\"itemId\": \"").append(Json.escape(r.itemId()))
                        .append("\", \"reasonCode\": \"").append(Json.escape(r.reasonCode()))
                        .append("\", \"detail\": \"").append(Json.escape(r.detail())).append("\"}");
            }
            sb.append(rejected.isEmpty() ? "],\n" : "\n  ],\n");

            sb.append("  \"stats\": {\"requested\": ").append(stats.requested())
                    .append(", \"accepted\": ").append(stats.accepted())
                    .append(", \"rejected\": ").append(stats.rejected())
                    .append(", \"outOfScope\": ").append(stats.outOfScope()).append("}\n");
            sb.append("}");
            return sb.toString();
        }
    }

    // ---------------- 解析辅助 ----------------

    static String asString(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    static double asDouble(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        // 字段缺失或类型不对，一律按 0 处理——即"低置信度"。
        // 绝不默认成 1.0，那是最危险的默认值。
        return 0d;
    }

    static List<String> asStringList(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> list) {
            for (Object e : list) {
                out.add(asString(e));
            }
        }
        return out;
    }
}
