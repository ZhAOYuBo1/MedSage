package com.medsage.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 风控服务
 *
 * 检测敏感内容，命中黑名单则中断流式输出
 * 使用精确短语匹配，避免误伤正常回答
 */
@Service
public class RiskControlService {

    private static final Logger log = LoggerFactory.getLogger(RiskControlService.class);

    /**
     * 黑名单 - 精确短语匹配
     * 命中任意一个即判定为敏感内容
     */
    private static final Set<String> BLACKLIST = Set.of(
            // 自伤类
            "如何自杀", "自杀方法", "怎么自杀", "自杀教程",
            "如何自残", "自残方法", "怎么自残",
            "轻生方法", "如何轻生", "怎样轻生",
            "寻死方法", "如何寻死",

            // 伤害他人
            "如何杀人", "杀人方法", "怎么杀人",
            "如何伤害他人", "伤害他人方法",

            // 毒品类
            "制造毒品", "贩卖毒品", "如何制毒",
            "制毒方法", "贩毒方法",

            // 药物滥用
            "药物过量致死", "怎样服药自杀", "过量服药方法",
            "服药自杀方法", "如何服药自杀"
    );

    /**
     * 检测是否命中黑名单
     *
     * @param text 累积文本
     * @return true 表示命中黑名单，应中断输出
     */
    public boolean isBlocked(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }

        boolean blocked = BLACKLIST.stream().anyMatch(text::contains);

        if (blocked) {
            log.warn("风控命中黑名单: text={}", text.substring(0, Math.min(50, text.length())));
        }

        return blocked;
    }
}
