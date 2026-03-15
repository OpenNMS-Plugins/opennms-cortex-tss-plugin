package org.opennms.timeseries.cortex;

import java.io.IOException;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Collections;
import java.util.Spliterators;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.json.JSONArray;
import org.json.JSONObject;
import org.opennms.integration.api.v1.distributed.KeyValueStore;
import org.opennms.integration.api.v1.timeseries.IntrinsicTagNames;
import org.opennms.integration.api.v1.timeseries.Metric;
import org.opennms.integration.api.v1.timeseries.Sample;
import org.opennms.integration.api.v1.timeseries.immutables.ImmutableMetric;
import org.opennms.integration.api.v1.timeseries.immutables.ImmutableSample;
import org.opennms.integration.api.v1.timeseries.immutables.ImmutableTag;

import static org.opennms.timeseries.cortex.CortexTSS.CORTEX_TSS;
import static org.opennms.timeseries.cortex.CortexTSS.INTRINSIC_TAG_NAMES;
import static org.opennms.timeseries.cortex.CortexTSS.METRIC_NAME_LABEL;


public class ResultMapper {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonFactory JSON_FACTORY = new JsonFactory(MAPPER);

    private ResultMapper(){
    }

    public static List<Sample> fromRangeQueryResult(final String queryResult, final Metric metric) {
        return iteratorToFiniteStream(new JSONObject(queryResult)
                .getJSONObject("data")
                .getJSONArray("result")
                .iterator())
                .map(j -> toSamples(((JSONObject)j), metric))
                .flatMap(List::stream)
                .collect(Collectors.toList());
    }

    public static List<Sample> toSamples(final JSONObject result, final Metric metric) {
        // final org.opennms.integration.api.v1.timeseries.Metric metric = toMetricFromMap(result.getJSONObject("metric").toMap());
        return iteratorToFiniteStream(result.getJSONArray("values").iterator())
                .map(value -> toSample(metric, (JSONArray)value)).collect(Collectors.toList());
    }

    public static Sample toSample(final Metric metric, final JSONArray jarray) {
        return ImmutableSample.builder()
                .time(Instant.ofEpochSecond(jarray.getLong(0)))
                .value(jarray.getDouble(1))
                .metric(metric)
                .build();
    }


    public static List<Metric> fromSeriesQueryResult(
            final String queryResult,
            final KeyValueStore store)  {

       return parseMetrics(queryResult,store);

    }


    public static <T> Metric toMetricFromMap(Map<String, T> tags) {
        ImmutableMetric.MetricBuilder metric = ImmutableMetric.builder();

        for (Map.Entry<String, T> entry : tags.entrySet()) {
            final String labelName = entry.getKey();
            final String labelValue = entry.getValue().toString();

            if (METRIC_NAME_LABEL.equals(labelName)) {
                metric.intrinsicTag(IntrinsicTagNames.name, labelValue);
            } else if (INTRINSIC_TAG_NAMES.contains(labelName)) {
                metric.intrinsicTag(labelName, labelValue);
            } else  {
                metric.metaTag(labelName, labelValue);
            }
        }
        return metric.build();
    }

    static <T> Stream<T> iteratorToFiniteStream(final Iterator<T> iterator) {
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(iterator, 0), false);
    }

    static Metric appendExternalTagsToMetric(final Metric metric, final KeyValueStore store) {
        var externalTagsRaw = store.get(metric.getKey(), CORTEX_TSS);
        if (externalTagsRaw.isPresent()) {
            final ImmutableMetric.MetricBuilder builder = new ImmutableMetric.MetricBuilder();
            builder.intrinsicTags(metric.getIntrinsicTags());
            builder.metaTags(metric.getMetaTags());
            new JSONObject(externalTagsRaw.get().toString()).toMap().forEach((k, v) -> {
                builder.externalTag(new ImmutableTag(k, v.toString()));
            });
            return builder.build();
        } else return metric;
    }

    /**
     * Parses a Prometheus label values API response.
     * Expected format: {"status":"success","data":["value1","value2",...]}
     * See: https://prometheus.io/docs/prometheus/latest/querying/api/#querying-label-values
     */
    public static List<String> parseLabelValuesResponse(final String json) {
        JSONObject response = new JSONObject(json);
        JSONArray data = response.getJSONArray("data");
        List<String> values = new ArrayList<>(data.length());
        for (int i = 0; i < data.length(); i++) {
            values.add(data.getString(i));
        }
        return values;
    }

    private static List<Metric> parseMetrics(String json,KeyValueStore store)  {
        try (JsonParser p = JSON_FACTORY.createParser(json)) {

            if (p.nextToken() != JsonToken.START_OBJECT) {
                throw new IOException("Invalid JSON");
            }


            while (p.nextToken() != JsonToken.END_OBJECT) {
                if ("data".equals(p.getCurrentName()) && p.nextToken() == JsonToken.START_ARRAY) {

                    if (p.nextToken() != JsonToken.START_OBJECT) {
                        return Collections.emptyList();
                    }

                    MappingIterator<Map<String, String>> iterator =
                            MAPPER.readValues(p, new TypeReference<Map<String, String>>() {});

                    List<Metric> list = new ArrayList<>();
                    while (iterator.hasNext()) {
                        list.add(
                                appendExternalTagsToMetric(
                                toMetricFromMap(iterator.next()), store));
                    }
                    return list;
                }
                p.skipChildren();
            }
        } catch (JsonParseException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return Collections.emptyList();
    }




}
