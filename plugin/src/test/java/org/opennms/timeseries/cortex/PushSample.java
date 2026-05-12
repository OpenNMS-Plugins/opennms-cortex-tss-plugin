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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.opennms.integration.api.v1.distributed.KeyValueStore;
import org.opennms.integration.api.v1.timeseries.IntrinsicTagNames;
import org.opennms.integration.api.v1.timeseries.Metric;
import org.opennms.integration.api.v1.timeseries.Sample;
import org.opennms.integration.api.v1.timeseries.StorageException;
import org.opennms.integration.api.v1.timeseries.immutables.ImmutableMetric;
import org.opennms.integration.api.v1.timeseries.immutables.ImmutableSample;

import static org.mockito.Mockito.mock;

public class PushSample {
    public static void main(String[] args) throws StorageException {
        Random random = new Random(42);
        KeyValueStore store = mock(KeyValueStore.class);
        CortexTSS cortexTss = new CortexTSS(CortexTSSConfig.builder().build(), store);
        Metric metric = ImmutableMetric.builder()
                .intrinsicTag(IntrinsicTagNames.resourceId, "response:127.0.0.1:icmp")
                .intrinsicTag(IntrinsicTagNames.name, "icmp")
                .metaTag("_idx2", "(response:127.0.0.1:icmp,3)")
                .metaTag("mtype", "gauge")
                .metaTag("ICMP/127.0.0.1", "icmp")
                .metaTag("_idx0", "(response,3)")
                .metaTag("_idx1", "(response:127.0.0.1,3)")
                .metaTag("_idx2w", "(response:127.0.0.1,*)")
                .build();
        List<Sample> samples = new ArrayList<>();
        for(int i=10000; i>0; i--) {
            samples.add(ImmutableSample.builder()
                    .metric(metric)
                    .value((double)random.nextInt(8000 - 3000) + 3000)
                    .time(Instant.now().minus(i*10, ChronoUnit.MINUTES)).build());
        }
        cortexTss.store(samples);
    }
}
