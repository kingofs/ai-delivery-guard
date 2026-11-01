package com.wsw.guard;

/**
 * 原因码。对外契约，只能加不能改——改一个码，前端和报表就全断。
 */
public interface Rule {

    /** finish_reason 不是 stop：输出被截断，整包不可用。 */
    String TRUNCATED = "E_TRUNCATED";
    /** 返回了请求清单外的 itemId：编造或串项。 */
    String ITEM_NOT_REQUESTED = "E_ITEM_NOT_REQUESTED";
    /** result 不是枚举值。 */
    String RESULT_NOT_ENUM = "E_RESULT_NOT_ENUM";
    /** 置信度低于阈值。 */
    String LOW_CONFIDENCE = "E_LOW_CONFIDENCE";
    /** 结论与置信度自相矛盾（例如 UNKNOWN 却 0.99）。 */
    String CONF_CONFLICT = "E_CONF_CONFLICT";
    /** 证据在原文中找不到。 */
    String EVIDENCE_NOT_FOUND = "E_EVIDENCE_NOT_FOUND";
    /** 证据过短，不构成证据。 */
    String EVIDENCE_TOO_SHORT = "E_EVIDENCE_TOO_SHORT";
    /** 请求项没有对应结论。 */
    String ITEM_MISSING = "E_ITEM_MISSING";
    /** 同项多条结论互相冲突。 */
    String CONFLICT = "E_CONFLICT";
    /** 该项禁止模型下结论。 */
    String FORBIDDEN_ITEM = "E_FORBIDDEN_ITEM";
    /** 模型没返回任何结论。 */
    String EMPTY_OUTPUT = "E_EMPTY_OUTPUT";
}
