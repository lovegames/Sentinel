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
package com.alibaba.csp.sentinel.dashboard.repository.metric;

import com.alibaba.csp.sentinel.dashboard.config.MetricStorageProperties;
import com.alibaba.csp.sentinel.dashboard.datasource.entity.MetricEntity;
import com.alibaba.csp.sentinel.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * H2 database based metrics repository.
 * <p>
 * Uses two-level storage strategy:
 * - Second-level data: kept for 1 hour (default) for precise queries
 * - Minute-level aggregated data: kept for 2 days (default) for historical queries
 * </p>
 *
 * @author Sentinel
 */
@Component
@ConditionalOnProperty(name = "sentinel.dashboard.metric.storage.enabled", havingValue = "true", matchIfMissing = true)
public class H2MetricsRepository implements MetricsRepository<MetricEntity> {

    private static final Logger logger = LoggerFactory.getLogger(H2MetricsRepository.class);

    private final JdbcTemplate jdbcTemplate;
    private final MetricStorageProperties properties;
    private final ScheduledExecutorService scheduledExecutor;

    private final RowMapper<MetricEntity> metricRowMapper = new MetricRowMapper();

    public H2MetricsRepository(JdbcTemplate jdbcTemplate, MetricStorageProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.scheduledExecutor = Executors.newSingleThreadScheduledExecutor(
                r -> {
                    Thread t = new Thread(r, "h2-metrics-scheduler");
                    t.setDaemon(true);
                    return t;
                });
    }

    @PostConstruct
    public void init() {
        initDatabase();
        startScheduledTasks();
        logger.info("H2MetricsRepository initialized. Retention: {}h for minute data, {}m for second data",
                properties.getRetentionHours(), properties.getSecondRetentionMinutes());
    }

    @PreDestroy
    public void destroy() {
        scheduledExecutor.shutdown();
        logger.info("H2MetricsRepository destroyed");
    }

    private void initDatabase() {
        // Create second-level data table
        jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS metric_second (" +
                        "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                        "app VARCHAR(128) NOT NULL, " +
                        "resource VARCHAR(256) NOT NULL, " +
                        "timestamp BIGINT NOT NULL, " +
                        "pass_qps BIGINT DEFAULT 0, " +
                        "success_qps BIGINT DEFAULT 0, " +
                        "block_qps LONG DEFAULT 0, " +
                        "exception_qps BIGINT DEFAULT 0, " +
                        "rt DOUBLE DEFAULT 0, " +
                        "count INT DEFAULT 0, " +
                        "gmt_create TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                        "CONSTRAINT uk_app_res_ts_second UNIQUE (app, resource, timestamp)" +
                        ")"
        );

        // Create minute-level aggregated table
        jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS metric_minute (" +
                        "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                        "app VARCHAR(128) NOT NULL, " +
                        "resource VARCHAR(256) NOT NULL, " +
                        "minute_timestamp BIGINT NOT NULL, " +
                        "pass_qps BIGINT DEFAULT 0, " +
                        "success_qps BIGINT DEFAULT 0, " +
                        "block_qps BIGINT DEFAULT 0, " +
                        "exception_qps BIGINT DEFAULT 0, " +
                        "rt DOUBLE DEFAULT 0, " +
                        "count INT DEFAULT 0, " +
                        "gmt_create TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                        "CONSTRAINT uk_app_res_min UNIQUE (app, resource, minute_timestamp)" +
                        ")"
        );

        // Create indexes
        createIndexIfNotExists("idx_second_ts", "metric_second", "timestamp");
        createIndexIfNotExists("idx_second_app", "metric_second", "app, timestamp");
        createIndexIfNotExists("idx_minute_ts", "metric_minute", "minute_timestamp");
        createIndexIfNotExists("idx_minute_app", "metric_minute", "app, minute_timestamp");

        logger.info("H2 database tables and indexes created");
    }

    private void createIndexIfNotExists(String indexName, String tableName, String columns) {
        try {
            jdbcTemplate.execute(String.format("CREATE INDEX IF NOT EXISTS %s ON %s (%s)", indexName, tableName, columns));
        } catch (Exception e) {
            logger.debug("Index {} may already exist: {}", indexName, e.getMessage());
        }
    }

    private void startScheduledTasks() {
        // Aggregate second-level data to minute-level every 5 minutes
        scheduledExecutor.scheduleAtFixedRate(this::aggregateToMinuteLevel, 5, 5, TimeUnit.MINUTES);

        // Clean up expired data every hour
        scheduledExecutor.scheduleAtFixedRate(this::cleanupExpiredData, 1, 1, TimeUnit.HOURS);
    }

    @Override
    public void save(MetricEntity entity) {
        if (entity == null || StringUtil.isBlank(entity.getApp())) {
            return;
        }
        long timestamp = entity.getTimestamp().getTime();

        String sql = "MERGE INTO metric_second (app, resource, timestamp, pass_qps, success_qps, " +
                "block_qps, exception_qps, rt, count) KEY (app, resource, timestamp) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

        jdbcTemplate.update(sql,
                entity.getApp(),
                entity.getResource(),
                timestamp,
                entity.getPassQps() != null ? entity.getPassQps() : 0L,
                entity.getSuccessQps() != null ? entity.getSuccessQps() : 0L,
                entity.getBlockQps() != null ? entity.getBlockQps() : 0L,
                entity.getExceptionQps() != null ? entity.getExceptionQps() : 0L,
                entity.getRt(),
                entity.getCount()
        );
    }

    @Override
    public void saveAll(Iterable<MetricEntity> metrics) {
        if (metrics == null) {
            return;
        }

        List<MetricEntity> list = new ArrayList<>();
        metrics.forEach(list::add);
        if (list.isEmpty()) {
            return;
        }

        String sql = "MERGE INTO metric_second (app, resource, timestamp, pass_qps, success_qps, " +
                "block_qps, exception_qps, rt, count) KEY (app, resource, timestamp) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

        jdbcTemplate.batchUpdate(sql, list, 1000, (ps, entity) -> {
            ps.setString(1, entity.getApp());
            ps.setString(2, entity.getResource());
            ps.setLong(3, entity.getTimestamp().getTime());
            ps.setLong(4, entity.getPassQps() != null ? entity.getPassQps() : 0L);
            ps.setLong(5, entity.getSuccessQps() != null ? entity.getSuccessQps() : 0L);
            ps.setLong(6, entity.getBlockQps() != null ? entity.getBlockQps() : 0L);
            ps.setLong(7, entity.getExceptionQps() != null ? entity.getExceptionQps() : 0L);
            ps.setDouble(8, entity.getRt());
            ps.setInt(9, entity.getCount());
        });
    }

    @Override
    public List<MetricEntity> queryByAppAndResourceBetween(String app, String resource, long startTime, long endTime) {
        List<MetricEntity> results = new ArrayList<>();
        if (StringUtil.isBlank(app)) {
            return results;
        }

        long now = System.currentTimeMillis();
        long secondRetentionMs = properties.getSecondRetentionMs();

        // Query second-level data (recent data within retention period)
        if (endTime > now - secondRetentionMs) {
            long queryStart = Math.max(startTime, now - secondRetentionMs);
            results.addAll(querySecondLevelData(app, resource, queryStart, endTime));
        }

        // Query minute-level aggregated data (older data)
        if (startTime < now - secondRetentionMs) {
            long queryEnd = Math.min(endTime, now - secondRetentionMs);
            results.addAll(queryMinuteLevelData(app, resource, startTime, queryEnd));
        }

        return results;
    }

    private List<MetricEntity> querySecondLevelData(String app, String resource, long startTime, long endTime) {
        String sql = "SELECT app, resource, timestamp, pass_qps, success_qps, block_qps, " +
                "exception_qps, rt, count FROM metric_second " +
                "WHERE app = ? AND resource = ? AND timestamp >= ? AND timestamp <= ? " +
                "ORDER BY timestamp ASC";

        return jdbcTemplate.query(sql, metricRowMapper, app, resource, startTime, endTime);
    }

    private List<MetricEntity> queryMinuteLevelData(String app, String resource, long startTime, long endTime) {
        // Align to minute boundary
        long startMinute = (startTime / 60000) * 60000;
        long endMinute = (endTime / 60000) * 60000;

        String sql = "SELECT app, resource, minute_timestamp as timestamp, pass_qps, success_qps, " +
                "block_qps, exception_qps, rt, count FROM metric_minute " +
                "WHERE app = ? AND resource = ? AND minute_timestamp >= ? AND minute_timestamp <= ? " +
                "ORDER BY minute_timestamp ASC";

        return jdbcTemplate.query(sql, metricRowMapper, app, resource, startMinute, endMinute);
    }

    @Override
    public List<String> listResourcesOfApp(String app) {
        if (StringUtil.isBlank(app)) {
            return new ArrayList<>();
        }

        long now = System.currentTimeMillis();
        // Use the full retention period for listing resources
        long secondRetentionMs = properties.getSecondRetentionMs();
        long minuteRetentionMs = properties.getRetentionMs();

        // Merge resources from both tables - query within retention period
        String sql = "SELECT DISTINCT resource FROM (" +
                "SELECT resource FROM metric_second WHERE app = ? AND timestamp >= ? " +
                "UNION " +
                "SELECT resource FROM metric_minute WHERE app = ? AND minute_timestamp >= ?" +
                ") ORDER BY resource";

        try {
            return jdbcTemplate.queryForList(sql, String.class, app, now - secondRetentionMs, app, now - minuteRetentionMs);
        } catch (EmptyResultDataAccessException e) {
            return new ArrayList<>();
        }
    }

    /**
     * Aggregate second-level data older than retention period to minute-level.
     */
    private void aggregateToMinuteLevel() {
        try {
            long now = System.currentTimeMillis();
            long threshold = now - properties.getSecondRetentionMs();

            // Find the earliest second-level data that needs aggregation
            Long earliestData = queryEarliestSecondData();
            if (earliestData == null || earliestData >= threshold) {
                return;
            }

            // Aggregate data older than threshold into minute-level
            String insertSql = "MERGE INTO metric_minute (app, resource, minute_timestamp, " +
                    "pass_qps, success_qps, block_qps, exception_qps, rt, count) " +
                    "KEY (app, resource, minute_timestamp) " +
                    "SELECT app, resource, (timestamp / 60000 * 60000) as minute_timestamp, " +
                    "SUM(pass_qps), SUM(success_qps), SUM(block_qps), SUM(exception_qps), " +
                    "SUM(rt), SUM(count) " +
                    "FROM metric_second WHERE timestamp < ? " +
                    "GROUP BY app, resource, (timestamp / 60000 * 60000)";

            int rows = jdbcTemplate.update(insertSql, threshold);
            if (rows > 0) {
                logger.info("Aggregated {} rows to minute-level", rows);
            }

            // Delete aggregated second-level data
            jdbcTemplate.update("DELETE FROM metric_second WHERE timestamp < ?", threshold);

        } catch (Exception e) {
            logger.error("Error aggregating to minute level", e);
        }
    }

    private Long queryEarliestSecondData() {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT MIN(timestamp) FROM metric_second",
                    Long.class
            );
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /**
     * Clean up expired data from both tables.
     */
    private void cleanupExpiredData() {
        try {
            long now = System.currentTimeMillis();
            long secondThreshold = now - properties.getSecondRetentionMs();
            long minuteThreshold = now - properties.getRetentionMs();

            // Clean second-level data (should already be cleaned by aggregation)
            int secondDeleted = jdbcTemplate.update(
                    "DELETE FROM metric_second WHERE timestamp < ?", secondThreshold);

            // Clean minute-level data
            int minuteDeleted = jdbcTemplate.update(
                    "DELETE FROM metric_minute WHERE minute_timestamp < ?", minuteThreshold);

            if (secondDeleted > 0 || minuteDeleted > 0) {
                logger.info("Cleaned up expired data: {} second-level, {} minute-level",
                        secondDeleted, minuteDeleted);
            }

        } catch (Exception e) {
            logger.error("Error cleaning up expired data", e);
        }
    }

    /**
     * Row mapper for MetricEntity.
     */
    private static class MetricRowMapper implements RowMapper<MetricEntity> {
        @Override
        public MetricEntity mapRow(ResultSet rs, int rowNum) throws SQLException {
            MetricEntity entity = new MetricEntity();
            entity.setApp(rs.getString("app"));
            entity.setResource(rs.getString("resource"));
            Date timestamp = new Date(rs.getLong("timestamp"));
            entity.setTimestamp(timestamp);
            entity.setGmtCreate(timestamp);  // Set gmtCreate to avoid NullPointerException
            entity.setPassQps(rs.getLong("pass_qps"));
            entity.setSuccessQps(rs.getLong("success_qps"));
            entity.setBlockQps(rs.getLong("block_qps"));
            entity.setExceptionQps(rs.getLong("exception_qps"));
            entity.setRt(rs.getDouble("rt"));
            entity.setCount(rs.getInt("count"));
            return entity;
        }
    }
}