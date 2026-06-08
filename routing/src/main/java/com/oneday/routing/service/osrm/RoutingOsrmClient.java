package com.oneday.routing.service.osrm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.List;

/**
 * Thin OSRM {@code /table} client over M6's own node set (M6-D-009) — same engine/config as M3's
 * grid {@code OsrmClient}, different nodes (we route over {hub} ∪ vertices (∪ airport), not hex
 * centroids). Not a Spring bean; {@code TravelMatrixService} constructs it. Pattern copied from
 * grid, not imported (cross-module rule).
 */
public class RoutingOsrmClient {

    private static final Logger log = LoggerFactory.getLogger(RoutingOsrmClient.class);

    private final String baseUrl;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RoutingOsrmClient(String baseUrl, RestTemplate restTemplate) {
        this.baseUrl = baseUrl;
        this.restTemplate = restTemplate;
    }

    /**
     * latLons: list of {@code [lat, lon]} pairs in node-index order. Returns {@code durations[i][j]}
     * in seconds (i → j). Throws on transport/parse failure or a non-Ok OSRM code.
     */
    public double[][] getTable(List<double[]> latLons) {
        if (latLons.isEmpty()) return new double[0][0];

        StringBuilder coords = new StringBuilder();
        for (int i = 0; i < latLons.size(); i++) {
            if (i > 0) coords.append(";");
            double[] ll = latLons.get(i);
            coords.append(ll[1]).append(",").append(ll[0]); // OSRM expects lon,lat
        }

        String url = baseUrl + "/table/v1/driving/" + coords + "?annotations=duration";
        try {
            String responseBody = restTemplate.getForObject(url, String.class);
            OsrmTableResponse resp = objectMapper.readValue(responseBody, OsrmTableResponse.class);
            if (!"Ok".equals(resp.code())) {
                throw new RuntimeException("OSRM table returned code=" + resp.code());
            }
            return resp.durations();
        } catch (RestClientException | java.io.IOException e) {
            log.warn("OSRM table request failed: {}", e.getMessage());
            throw new RuntimeException("OSRM table request failed: " + e.getMessage(), e);
        }
    }
}
