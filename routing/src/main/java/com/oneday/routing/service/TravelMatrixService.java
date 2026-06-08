package com.oneday.routing.service;

import com.oneday.routing.service.model.RoutingNode;
import com.oneday.routing.service.model.TravelMatrix;
import com.oneday.routing.service.osrm.RoutingOsrmClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds M6's travel-time matrix for one planning run over its node set {@code {hub} ∪ vertices
 * (∪ airport)} (M6-D-009, §7.3). One OSRM {@code /table} call per run; the result is the
 * {@link TravelMatrix} the solver consumes. Caching across runs is not needed — the nightly job
 * builds it once per city per night.
 */
@Service
public class TravelMatrixService {

    /** Penalty (seconds, ~28h) for an OSRM-unreachable pair so the solver never routes through it. */
    private static final long UNREACHABLE_PENALTY_SECONDS = 100_000L;

    private final RoutingOsrmClient osrmClient;

    TravelMatrixService(RoutingOsrmClient osrmClient) {
        this.osrmClient = osrmClient;
    }

    /**
     * Build the matrix for {@code nodes} (must be in index order: {@code nodes.get(i).index() == i}).
     * Durations are rounded to whole seconds; unreachable / missing pairs get a large penalty.
     */
    public TravelMatrix buildMatrix(List<RoutingNode> nodes) {
        int n = nodes.size();
        List<double[]> latLons = new ArrayList<>(n);
        for (RoutingNode node : nodes) {
            latLons.add(new double[]{node.lat(), node.lon()});
        }

        double[][] durations = osrmClient.getTable(latLons);
        long[][] seconds = new long[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                double d = (durations.length > i && durations[i] != null && durations[i].length > j)
                        ? durations[i][j] : Double.NaN;
                seconds[i][j] = (i == j) ? 0L
                        : (Double.isNaN(d) || d < 0) ? UNREACHABLE_PENALTY_SECONDS
                        : Math.round(d);
            }
        }
        return new TravelMatrix(nodes, seconds);
    }
}
