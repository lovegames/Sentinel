/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.csp.sentinel.dashboard.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for metric storage.
 *
 * @author Sentinel
 */
@ConfigurationProperties(prefix = "sentinel.dashboard.metric.storage")
public class MetricStorageProperties {

    /**
     * Whether to enable persistent storage for metrics.
     * If false, use in-memory storage (default behavior, 5 minutes retention).
     */
    private boolean enabled = true;

    /**
     * Retention time for aggregated minute-level data (in hours).
     * Default: 48 hours (2 days).
     */
    private int retentionHours = 48;

    /**
     * Retention time for second-level data (in minutes).
     * Default: 60 minutes (1 hour).
     * Second-level data older than this will be aggregated to minute-level.
     */
    private int secondRetentionMinutes = 60;

    /**
     * Data path for H2 database files.
     * Default: ${user.home}/logs/csp/sentinel-metrics
     */
    private String dataPath = System.getProperty("user.home") + "/logs/csp/sentinel-metrics";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getRetentionHours() {
        return retentionHours;
    }

    public void setRetentionHours(int retentionHours) {
        this.retentionHours = retentionHours;
    }

    public int getSecondRetentionMinutes() {
        return secondRetentionMinutes;
    }

    public void setSecondRetentionMinutes(int secondRetentionMinutes) {
        this.secondRetentionMinutes = secondRetentionMinutes;
    }

    public String getDataPath() {
        return dataPath;
    }

    public void setDataPath(String dataPath) {
        this.dataPath = dataPath;
    }

    /**
     * Get retention time for minute-level data in milliseconds.
     */
    public long getRetentionMs() {
        return retentionHours * 60L * 60 * 1000;
    }

    /**
     * Get retention time for second-level data in milliseconds.
     */
    public long getSecondRetentionMs() {
        return secondRetentionMinutes * 60L * 1000;
    }
}