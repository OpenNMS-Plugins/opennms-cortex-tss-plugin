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

import org.opennms.integration.api.v1.distributed.KeyValueStore;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;

    public class KVStoreMock implements KeyValueStore {
        private Map<String, Object> kvStore = new HashMap<>();

        @Override
        public long put(String key, Object value, String context) {
            kvStore.put(key, value);
            return 0L;
        }

        @Override
        public long put(String key, Object value, String context, Integer ttlInSeconds) {
            kvStore.put(key, value);
            return 0;
        }

        @Override
        public Optional get(String key, String context) {
            if (kvStore.get(key) != null)
                return Optional.of(kvStore.get(key));
            else return Optional.empty();
        }

        @Override
        public Optional getIfStale(String key, String context, long timestamp) {
            throw new RuntimeException();
        }

        @Override
        public OptionalLong getLastUpdated(String key, String context) {
            throw new RuntimeException();
        }

        @Override
        public Map enumerateContext(String context) {
            return kvStore;
        }

        @Override
        public void delete(String key, String context) {
            throw new RuntimeException();
        }

        @Override
        public void truncateContext(String context) {
            throw new RuntimeException();
        }

        @Override
        public CompletableFuture<Long> putAsync(String key, Object value, String context) {
            kvStore.put(key, value);
            return CompletableFuture.completedFuture(0L);
        }

        @Override
        public CompletableFuture<Long> putAsync(String key, Object value, String context, Integer ttlInSeconds) {
            kvStore.put(key, value);
            return CompletableFuture.completedFuture(0L);
        }

        @Override
        public CompletableFuture<Optional> getAsync(String key, String context) {
            throw new RuntimeException();
        }

        @Override
        public CompletableFuture<Optional> getIfStaleAsync(String key, String context, long timestamp) {
            throw new RuntimeException();
        }

        @Override
        public CompletableFuture<OptionalLong> getLastUpdatedAsync(String key, String context) {
            throw new RuntimeException();
        }

        @Override
        public String getName() {
            return this.getClass().getCanonicalName();
        }

        @Override
        public CompletableFuture<Map> enumerateContextAsync(String context) {
            return CompletableFuture.completedFuture(kvStore);
        }

        @Override
        public CompletableFuture<Void> deleteAsync(String key, String context) {
            throw new RuntimeException();
        }

        @Override
        public CompletableFuture<Void> truncateContextAsync(String context) {
            throw new RuntimeException();
        }

}
