package com.goshare.assistant.llm;

import java.util.List;
import java.util.Map;

/**
 * 规则答案提供器（省 token 关卡2「小模型先答」的零成本实现）：
 * 固定前缀意图（通用规则/流程类问题）直接命中规则模板作答，不产生任何模型调用。
 * 仅回答通用规则，不涉及具体订单金额——具体金额一律由订单服务的 FeeCalculator 计算。
 */
public final class RuleAnswerProvider {

    private static final List<Map.Entry<String, String>> RULES = List.of(
            Map.entry("你是谁", "我是 GoShare 顺风拼车平台的 AI 出行助手。可以帮你查行程、算费用、解释取消与用券规则，也会在你要取消订单时先帮你算清违约金。有什么出行问题尽管问我。"),
            Map.entry("取消收费", "【通用规则】城际订单发车前 2 小时外取消不收取违约金；2 小时内取消按实付金额的 20% 收取违约金（单笔封顶 50 元）。具体这单扣多少，请提供订单号，我来帮你精确计算。"),
            Map.entry("违约金", "【通用规则】取消违约金按距发车时间分档：提前 2 小时以上免违约金；2 小时以内按实付金额 20% 收取，封顶 50 元。"),
            Map.entry("退钱", "【通用规则】取消后退款原路返回：发车前 2 小时外全额退款；2 小时内扣除违约金后退回剩余部分，通常 1-3 个工作日到账。"),
            Map.entry("候补", "【通用规则】行程满座时可以先登记候补，有座位释放会按登记顺序自动补位；候补期间可随时退出。"),
            Map.entry("用券", "【通用规则】下单时可用一张满减/折扣/立减券；取消或超时关闭订单后券会退回。"),
            Map.entry("扣钱", "【通用规则】只有临近发车（2 小时内）取消才可能产生违约金（实付 20%、封顶 50 元），其余情况取消不扣钱。提供订单号可精确计算。"),
            Map.entry("扣费", "【通用规则】取消订单的扣费规则：发车前 2 小时外不扣费；2 小时内按实付 20% 扣违约金（封顶 50 元）。"),
            Map.entry("怎么收费", "【通用规则】顺风车按行程定价收费：下单时支付行程价格，可用优惠券抵扣；取消订单按取消规则收取/免除违约金。"),
            Map.entry("费用", "【通用规则】顺风车费用 = 行程价格 - 优惠券抵扣；退款与违约金按距发车时间计算，提供订单号可精确核算。")
    );

    private RuleAnswerProvider() {
    }

    /**
     * 命中返回规则答案，未命中返回 null
     */
    public static String maybeAnswer(String question) {
        for (Map.Entry<String, String> rule : RULES) {
            if (question.contains(rule.getKey())) {
                return rule.getValue();
            }
        }
        return null;
    }
}
