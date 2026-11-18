package com.qoder.cs.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "cs")
public class CsProperties {

    private Plan plan = new Plan();
    private Free free = new Free();
    private Kb kb = new Kb();
    private Chat chat = new Chat();
    private Proactive proactive = new Proactive();
    /** 套餐目录（配置化）：月价/千 token 单价/日 token 预算/并发，用于展示与账单计费 */
    private Map<String, PlanSpec> plans = defaultPlans();

    private static Map<String, PlanSpec> defaultPlans() {
        Map<String, PlanSpec> m = new LinkedHashMap<>();
        m.put("free", new PlanSpec("免费版", 0, 0, 0, 1));
        m.put("standard", new PlanSpec("标准版", 9900, 10, 300_000, 2));
        m.put("pro", new PlanSpec("专业版", 29900, 8, 2_000_000, 8));
        return m;
    }

    /** 取套餐规格，未知编码回落 standard。 */
    public PlanSpec planSpec(String code) {
        PlanSpec s = code == null ? null : plans.get(code);
        return s != null ? s : plans.getOrDefault("standard", new PlanSpec("标准版", 9900, 10, 300_000, 2));
    }

    @Data
    public static class PlanSpec {
        private String name;
        /** 月价（分） */
        private long monthlyPrice;
        /** 千 token 单价（分），账单按用量计费 */
        private long tokenPricePerK;
        /** 日 token 预算（0=不限） */
        private long dailyTokenBudget;
        /** LLM 并发上限 */
        private int concurrency;

        public PlanSpec() {
        }

        public PlanSpec(String name, long monthlyPrice, long tokenPricePerK, long dailyTokenBudget, int concurrency) {
            this.name = name;
            this.monthlyPrice = monthlyPrice;
            this.tokenPricePerK = tokenPricePerK;
            this.dailyTokenBudget = dailyTokenBudget;
            this.concurrency = concurrency;
        }
    }

    @Data
    public static class Plan {
        private String name = "标准版";
        private int days = 30;
        /** 价格（分） */
        private long price = 9900;
    }

    @Data
    public static class Free {
        private int dailyQuota = 5;
    }

    @Data
    public static class Kb {
        /** FAQ 直答的 BM25 最低分阈值 */
        private double directThreshold = 1.0;
        /** FAQ 直答的向量 cosine 最低分阈值（int8 bge-small-zh 实测：同主题改写 0.66~0.81，跨主题 ≤0.50） */
        private double vectorDirectThreshold = 0.65;
        private int ragTopK = 3;
    }

    @Data
    public static class Chat {
        /** 历史窗口轮数 */
        private int historyRounds = 10;
        /** 工具调用最大轮数 */
        private int maxToolRounds = 3;
        /** 历史重建字符预算（自最近消息累加，超出截断；含工具轨迹注入文本） */
        private int maxHistoryChars = 6000;
        /** 每租户 LLM 并发上限（0=不限）；超限立即 429，不排队 */
        private int tenantConcurrency = 2;
    }

    @Data
    public static class Proactive {
        /** 主动服务轮询开关（上游未跑时自动跳过，不影响对话） */
        private boolean enabled = true;
        /** 只关注最近 N 小时内产生的异常事件，避免首启对历史数据刷屏 */
        private int lookbackHours = 24;
        /** 单轮每类事件最多推送条数 */
        private int maxPerPoll = 10;
    }
}