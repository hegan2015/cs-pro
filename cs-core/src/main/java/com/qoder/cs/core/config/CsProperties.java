package com.qoder.cs.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "cs")
public class CsProperties {

    private Plan plan = new Plan();
    private Free free = new Free();
    private Kb kb = new Kb();
    private Chat chat = new Chat();

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
        private int ragTopK = 3;
    }

    @Data
    public static class Chat {
        /** 历史窗口轮数 */
        private int historyRounds = 10;
        /** 工具调用最大轮数 */
        private int maxToolRounds = 3;
    }
}