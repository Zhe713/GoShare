package com.goshare.assistant.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 省 token 关卡2「小模型先答」规则命中测试（零调用兜底）
 */
class RuleAnswerProviderTest {

    @Test
    void 取消收费规则命中() {
        String ans = RuleAnswerProvider.maybeAnswer("取消订单会扣钱吗");
        assertNotNull(ans);
        assertTrue(ans.contains("违约金"));
    }

    @Test
    void 候补规则命中() {
        String ans = RuleAnswerProvider.maybeAnswer("行程满了，候补多久能排到？");
        assertNotNull(ans);
        assertTrue(ans.contains("候补"));
    }

    @Test
    void 用券规则命中() {
        String ans = RuleAnswerProvider.maybeAnswer("用券之后取消券会退吗？");
        assertNotNull(ans);
        assertTrue(ans.contains("退回"));
    }

    @Test
    void 无关问题不命中() {
        assertNull(RuleAnswerProvider.maybeAnswer("帮我查一下明天天气"));
    }
}
