/*******************************************************************************
 * This file is part of OpenNMS(R).
 *
 * Copyright (C) 2021 The OpenNMS Group, Inc.
 * OpenNMS(R) is Copyright (C) 1999-2021 The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is a registered trademark of The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * OpenNMS(R) is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with OpenNMS(R).  If not, see:
 *      http://www.gnu.org/licenses/
 *
 * For more information contact:
 *     OpenNMS(R) Licensing <license@opennms.org>
 *     http://www.opennms.org/
 *     http://www.opennms.com/
 *******************************************************************************/

package org.opennms.timeseries.cortex;

import java.util.Objects;
import java.util.StringJoiner;

public class CortexTSSConfig {
    private final String writeUrl;
    private final String readUrl;
    private final int maxConcurrentHttpConnections;
    private final long writeTimeoutInMs;
    private final long readTimeoutInMs;
    private final long metricCacheSize;
    private final long externalTagsCacheSize;
    private final long bulkheadMaxWaitDurationInMs;
    private final long maxSeriesLookback;
    private final String organizationId;
    private final boolean hasOrganizationId;
    private final boolean useLabelValuesForDiscovery;
    private final int discoveryBatchSize;

    public CortexTSSConfig() {
        this(builder());
    }

    public CortexTSSConfig(Builder builder) {
        this.writeUrl = Objects.requireNonNull(builder.writeUrl);
        this.readUrl = Objects.requireNonNull(builder.readUrl);
        this.maxConcurrentHttpConnections = builder.maxConcurrentHttpConnections;
        this.writeTimeoutInMs = builder.writeTimeoutInMs;
        this.readTimeoutInMs = builder.readTimeoutInMs;
        this.metricCacheSize = builder.metricCacheSize;
        this.externalTagsCacheSize = builder.externalTagsCacheSize;
        this.bulkheadMaxWaitDurationInMs = builder.bulkheadMaxWaitDurationInMs;
        this.maxSeriesLookback = builder.maxSeriesLookback;
        this.organizationId = builder.organizationId;
        this.hasOrganizationId = organizationId != null && organizationId.trim().length() > 0;
        this.useLabelValuesForDiscovery = builder.useLabelValuesForDiscovery;
        this.discoveryBatchSize = builder.discoveryBatchSize;
    }

    /** Will be called via blueprint. The builder can be called when not running as Osgi plugin. */
    public CortexTSSConfig(
            final String writeUrl,
            final String readUrl,
            final int maxConcurrentHttpConnections,
            final long writeTimeoutInMs,
            final long readTimeoutInMs,
            final long metricCacheSize,
            final long externalTagsCacheSize,
            final long bulkheadMaxWaitDurationInMs,
            final long maxSeriesLookback,
            final String organizationId,
            final boolean useLabelValuesForDiscovery,
            final int discoveryBatchSize) {
        this(builder()
                .writeUrl(writeUrl)
                .readUrl(readUrl)
                .maxConcurrentHttpConnections(maxConcurrentHttpConnections)
                .writeTimeoutInMs(writeTimeoutInMs)
                .readTimeoutInMs(readTimeoutInMs)
                .metricCacheSize(metricCacheSize)
                .externalCacheSize(externalTagsCacheSize)
                .bulkheadMaxWaitDurationInMs(bulkheadMaxWaitDurationInMs)
                .maxSeriesLookback(maxSeriesLookback)
                .organizationId(organizationId)
                .useLabelValuesForDiscovery(useLabelValuesForDiscovery)
                .discoveryBatchSize(discoveryBatchSize));
    }

    public String getWriteUrl() {
        return writeUrl;
    }

    public String getReadUrl() {
        return readUrl;
    }

    public int getMaxConcurrentHttpConnections() {
        return maxConcurrentHttpConnections;
    }

    public long getWriteTimeoutInMs() {
        return writeTimeoutInMs;
    }

    public long getReadTimeoutInMs() {
        return readTimeoutInMs;
    }

    public long getMetricCacheSize() {
        return metricCacheSize;
    }

    public long getExternalTagsCacheSize() { return externalTagsCacheSize; }

    public long getBulkheadMaxWaitDurationInMs() {
        return bulkheadMaxWaitDurationInMs;
    }

    public long getMaxSeriesLookback() {
        return maxSeriesLookback;
    }

    public boolean hasOrganizationId() {
        return hasOrganizationId;
    }

    public String getOrganizationId() {
        return organizationId;
    }

    public boolean isUseLabelValuesForDiscovery() {
        return useLabelValuesForDiscovery;
    }

    public int getDiscoveryBatchSize() {
        return discoveryBatchSize;
    }

    public static Builder builder() {
        return new Builder();
    }

    public final static class Builder {
        private String writeUrl = "http://localhost:9009/api/prom/push";
        private String readUrl = "http://localhost:9009/prometheus/api/v1";
        private int maxConcurrentHttpConnections = 100;
        private long writeTimeoutInMs = 5000;
        private long readTimeoutInMs = 5000;
        private long metricCacheSize = 1000;
        private long externalTagsCacheSize = 1000;
        private long bulkheadMaxWaitDurationInMs = Long.MAX_VALUE;
        private long maxSeriesLookback = 7776000;
        private String organizationId = null;
        private boolean useLabelValuesForDiscovery = false;
        private int discoveryBatchSize = 50;

        public Builder writeUrl(final String writeUrl) {
            this.writeUrl = writeUrl;
            return this;
        }

        public Builder readUrl(final String readUrl) {
            this.readUrl = readUrl;
            return this;
        }

        public Builder maxConcurrentHttpConnections(final int maxConcurrentHttpConnections) {
            this.maxConcurrentHttpConnections = maxConcurrentHttpConnections;
            return this;
        }

        public Builder writeTimeoutInMs(final long writeTimeoutInMs) {
            this.writeTimeoutInMs = writeTimeoutInMs;
            return this;
        }

        public Builder readTimeoutInMs(final long readTimeoutInMs) {
            this.readTimeoutInMs = readTimeoutInMs;
            return this;
        }

        public Builder metricCacheSize(final long metricCacheSize) {
            this.metricCacheSize = metricCacheSize;
            return this;
        }

        public Builder externalCacheSize(final long externalTagsCacheSize) {
            this.externalTagsCacheSize = externalTagsCacheSize;
            return this;
        }

        public Builder bulkheadMaxWaitDurationInMs(final long bulkheadMaxWaitDurationInMs) {
            this.bulkheadMaxWaitDurationInMs = bulkheadMaxWaitDurationInMs;
            return this;
        }

        public Builder maxSeriesLookback (final long maxSeriesLookback) {
            this.maxSeriesLookback = maxSeriesLookback;
            return this;
        }
        public Builder organizationId(final String organizationId) {
            this.organizationId = organizationId;
            return this;
        }

        public Builder useLabelValuesForDiscovery(final boolean useLabelValuesForDiscovery) {
            this.useLabelValuesForDiscovery = useLabelValuesForDiscovery;
            return this;
        }

        public Builder discoveryBatchSize(final int discoveryBatchSize) {
            this.discoveryBatchSize = Math.max(1, discoveryBatchSize);
            return this;
        }

        public CortexTSSConfig build() {
            return new CortexTSSConfig(this);
        }
    }

    @Override
    public String toString() {
        return new StringJoiner(", ", CortexTSSConfig.class.getSimpleName() + "[", "]")
                .add("writeUrl='" + writeUrl + "'")
                .add("readUrl='" + readUrl + "'")
                .add("maxConcurrentHttpConnections=" + maxConcurrentHttpConnections)
                .add("writeTimeoutInMs=" + writeTimeoutInMs)
                .add("readTimeoutInMs=" + readTimeoutInMs)
                .add("metricCacheSize=" + metricCacheSize)
                .add("externalCacheSize=" + externalTagsCacheSize)
                .add("bulkheadMaxWaitDurationInMs=" + bulkheadMaxWaitDurationInMs)
                .add("maxSeriesLookback=" + maxSeriesLookback)
                .add("organizationId=" + organizationId)
                .add("useLabelValuesForDiscovery=" + useLabelValuesForDiscovery)
                .add("discoveryBatchSize=" + discoveryBatchSize)
                .toString();
    }
}
