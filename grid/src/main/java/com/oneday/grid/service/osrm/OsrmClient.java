package com.oneday.grid.service.osrm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.List;

// Not a Spring bean — callers construct it directly.
public class OsrmClient {

    private static final Logger log = LoggerFactory.getLogger(OsrmClient.class);

    private final String baseUrl;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OsrmClient(String baseUrl, RestTemplate restTemplate) {
        this.baseUrl = baseUrl;
        this.restTemplate = restTemplate;
    }

    // latLons: list of [lat, lon] pairs for tile centroids.
    // Returns durations[i][j] in seconds; null entry means OSRM returned null (unreachable pair).
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
            throw new RuntimeException("OSRM table request failed: " + e.getMessage(), e);
        }
    }

    // Returns road time in seconds from SW corner to NE corner of a single tile,
    // or null if OSRM is unreachable / route is not found.
    public Integer getTileTraversalCap(double swLat, double swLon, double neLat, double neLon) {
        String coords = swLon + "," + swLat + ";" + neLon + "," + neLat;
        String url = baseUrl + "/route/v1/driving/" + coords + "?overview=false";
        try {
            String responseBody = restTemplate.getForObject(url, String.class);
            var node = objectMapper.readTree(responseBody);
            if (!"Ok".equals(node.path("code").asText())) {
                log.debug("OSRM route returned non-Ok code for traversal cap at ({},{})→({},{})", swLat, swLon, neLat, neLon);
                return null;
            }
            return (int) node.path("routes").get(0).path("duration").asDouble();
        } catch (Exception e) {
            log.debug("OSRM traversal cap request failed: {}", e.getMessage());
            return null;
        }
    }
}
