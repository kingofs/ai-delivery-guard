package com.wsw.guard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 校验门禁本体。无状态，可并发复用。
 *
 * <p>三条原则，顺序不能反：
 * <ol>
 *   <li><b>技术故障整包拒绝</b>：截断、空输出。这类结果往下走只会制造脏数据。</li>
 *   <li><b>业务不确定逐项拒绝</b>：缺项、证据不足、置信度低。其余项照常交付，
 *       不能因为一项判不了就把整批打成失败。</li>
 *   <li><b>冲突整项丢弃</b>，不取"最新一条"：AI 重复输出且互相矛盾时，选哪条都是赌博。</li>
 * </ol>
 */
public final class Engine implements Rule {

    /** 低于该置信度的合规结论不下发。0.90 是个业务数字：写错直接影响执法结论。 */
    public static final double DEFAULT_MIN_CONFIDENCE = 0.90;
    /** 少于 4 个字的"证据"（"是""无""通过"）不构成证据。 */
    public static final int DEFAULT_MIN_EVIDENCE_LEN = 4;

    private static final Set<String> ALLOWED = new LinkedHashSet<>(List.of("PASS", "FAIL", "UNKNOWN", "NA"));

    private final double minConfidence;
    private final int minEvidenceLen;

    public Engine() {
        this(DEFAULT_MIN_CONFIDENCE, DEFAULT_MIN_EVIDENCE_LEN);
    }

    public Engine(double minConfidence, int minEvidenceLen) {
        this.minConfidence = minConfidence;
        this.minEvidenceLen = minEvidenceLen;
    }

    public Model.Response check(Model.Request req) {
        if (req == null) {
            return Model.Response.rejectedAll(EMPTY_OUTPUT, "请求为空", 0);
        }
        int requested = req.requestItems().size();

        // G0 截断检测。不看 finish_reason 只看 JSON 能否 parse，
        // 是"被 max_tokens 截断但前半段仍是合法 JSON"这类事故的根源。
        if (!"stop".equals(req.finishReason())) {
            String fr = req.finishReason() == null || req.finishReason().isBlank()
                    ? "(空)" : req.finishReason();
            return Model.Response.rejectedAll(TRUNCATED,
                    "finish_reason=" + fr + "，非 stop，输出可能被截断，整包不采用", requested);
        }

        if (req.verdicts().isEmpty()) {
            return Model.Response.rejectedAll(EMPTY_OUTPUT, "模型未返回任何结论", requested);
        }

        // 按 itemId 分组，保持首次出现顺序。
        Map<String, List<Model.Verdict>> grouped = new LinkedHashMap<>();
        List<String> order = new ArrayList<>();
        for (Model.Verdict v : req.verdicts()) {
            String id = v.itemId() == null ? "" : v.itemId().trim();
            if (!grouped.containsKey(id)) {
                order.add(id);
                grouped.put(id, new ArrayList<>());
            }
            grouped.get(id).add(new Model.Verdict(id, v.result(), v.evidence(), v.confidence()));
        }

        Set<String> requestedSet = new LinkedHashSet<>();
        for (String s : req.requestItems()) {
            requestedSet.add(s == null ? "" : s.trim());
        }
        Set<String> forbidden = new LinkedHashSet<>();
        for (String s : req.forbiddenItems()) {
            forbidden.add(s == null ? "" : s.trim());
        }

        List<Model.Verdict> accepted = new ArrayList<>();
        List<Model.Reject> rejected = new ArrayList<>();
        boolean incomplete = false;

        for (String rawId : req.requestItems()) {
            String id = rawId == null ? "" : rawId.trim();
            List<Model.Verdict> items = grouped.getOrDefault(id, List.of());

            if (items.isEmpty()) {
                // G5 缺项。不补一个"合理缺项"，明确报出来。
                incomplete = true;
                rejected.add(new Model.Reject(id, ITEM_MISSING, "请求项未返回结论，不采用推测填充"));
                continue;
            }

            // G7 业务禁判项。模型连结论都不该给。
            if (forbidden.contains(id)) {
                rejected.add(new Model.Reject(id, FORBIDDEN_ITEM, "该项属人工判定范围，模型结论不予采用"));
                continue;
            }

            // G6 同项多条。
            if (items.size() > 1) {
                String first = items.get(0).result() == null ? "" : items.get(0).result().trim();
                boolean conflict = false;
                for (int i = 1; i < items.size(); i++) {
                    String cur = items.get(i).result() == null ? "" : items.get(i).result().trim();
                    if (!first.equalsIgnoreCase(cur)) {
                        conflict = true;
                        break;
                    }
                }
                if (conflict) {
                    rejected.add(new Model.Reject(id, CONFLICT,
                            "同项返回 " + items.size() + " 条互相冲突的结论，整项丢弃而非择一采用"));
                    continue;
                }
                items = items.subList(0, 1);
            }

            Model.Reject rej = checkOne(id, items.get(0), req.sourceText());
            if (rej != null) {
                rejected.add(rej);
            } else {
                accepted.add(items.get(0));
            }
        }

        // G1 反向：清单外的 itemId。
        int outOfScope = 0;
        for (String id : order) {
            if (requestedSet.contains(id)) {
                continue;
            }
            outOfScope++;
            rejected.add(new Model.Reject(id, ITEM_NOT_REQUESTED,
                    "返回了本次请求清单外的检查项，视为编造或串项"));
        }

        Model.Stats stats = new Model.Stats(requested, accepted.size(), rejected.size() - outOfScope, outOfScope);
        boolean ok = !accepted.isEmpty();
        return new Model.Response(ok, false, incomplete, null, null, accepted, rejected, stats);
    }

    private Model.Reject checkOne(String itemId, Model.Verdict v, String source) {
        String result = v.result() == null ? "" : v.result().trim().toUpperCase(Locale.ROOT);

        // G2 枚举校验。
        if (!ALLOWED.contains(result)) {
            return new Model.Reject(itemId, RESULT_NOT_ENUM,
                    "result=\"" + (v.result() == null ? "" : v.result()) + "\" 不在枚举 [PASS FAIL UNKNOWN NA] 内");
        }

        // UNKNOWN / NA 是"判不了""不适用"，不要求证据，也不要求置信度。
        // 这一条必须放行：否则模型诚实地说"我不确定"，反而被门禁打死。
        if ("UNKNOWN".equals(result) || "NA".equals(result)) {
            if (v.confidence() >= 0.95) {
                return new Model.Reject(itemId, CONF_CONFLICT,
                        "结论为 UNKNOWN 但置信度 0.95 以上，输出自相矛盾");
            }
            return null;
        }

        // G3 置信度。缺省 0 不视为 1.0。
        if (v.confidence() < 0 || v.confidence() > 1) {
            return new Model.Reject(itemId, LOW_CONFIDENCE, "confidence 越界 [0,1]，视为无效");
        }
        if (v.confidence() < minConfidence) {
            return new Model.Reject(itemId, LOW_CONFIDENCE, "confidence 低于阈值，结论不下发，转人工补充");
        }

        // G4 证据逐字校验。
        String evidence = normalize(v.evidence());
        if (evidence.codePointCount(0, evidence.length()) < minEvidenceLen) {
            return new Model.Reject(itemId, EVIDENCE_TOO_SHORT,
                    "证据长度不足 " + minEvidenceLen + " 字，不构成有效证据");
        }
        if (!normalize(source).contains(evidence)) {
            return new Model.Reject(itemId, EVIDENCE_NOT_FOUND,
                    "证据在原始文本中不存在（逐字匹配失败），判定为模型编造");
        }
        return null;
    }

    /**
     * 归一化文本用于逐字比对。
     *
     * <p>只做格式层的容忍，不做语义层的模糊：去空白、全角 ASCII 转半角、统一小写。
     *
     * <p>故意不做：同义词、编辑距离、部分匹配。只要放过一条编造的证据，
     * 整批结论的可信度就归零——宁可比对失败（漏判），也不能让编造的证据通过（误放）。
     *
     * <p>跨语言坑：Java 的 {@link Character#isWhitespace(char)} 对全角空格 U+3000 返回 <b>false</b>
     * （它是 SPACE_SEPARATOR，不是空白控制符），必须再加 {@link Character#isSpaceChar(char)}。
     * 移植自 Go 时这里漏了就会导致"证据里混了全角空格就判不中"，而 Go 的 unicode.IsSpace 是包含 U+3000 的。
     */
    public static String normalize(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                continue;
            }
            if (c >= 0xFF01 && c <= 0xFF5E) {
                b.append((char) (c - 0xFEE0));
                continue;
            }
            b.append(c);
        }
        return b.toString().toLowerCase(Locale.ROOT);
    }
}
