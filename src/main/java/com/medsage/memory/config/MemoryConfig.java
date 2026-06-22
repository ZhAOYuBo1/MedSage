package com.medsage.memory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 长期记忆配置
 */
@Configuration
@ConfigurationProperties(prefix = "medsage.memory.long-term")
public class MemoryConfig {

    /** 向量召回 top-K */
    private int topK = 5;
    /** 是否启用自动召回 */
    private boolean autoRecallEnabled = true;

    public int getTopK() {
        return topK;
    }

    public void setTopK(int topK) {
        this.topK = topK;
    }

    public boolean isAutoRecallEnabled() {
        return autoRecallEnabled;
    }

    public void setAutoRecallEnabled(boolean autoRecallEnabled) {
        this.autoRecallEnabled = autoRecallEnabled;
    }
}
