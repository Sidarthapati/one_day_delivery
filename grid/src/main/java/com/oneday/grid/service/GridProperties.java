package com.oneday.grid.service;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "grid")
@Data
public class GridProperties {

    private Osrm osrm = new Osrm();
    private Solver solver = new Solver();
    private Bootstrap bootstrap = new Bootstrap();
    private Shift shift = new Shift();
    private Da da = new Da();
    private Intraday intraday = new Intraday();

    @Data
    public static class Osrm {
        private String baseUrl = "http://localhost:5000";
        private int adjacencyThresholdSeconds = 600;
    }

    @Data
    public static class Solver {
        private int timeLimitSeconds = 60;
        private double loadTolerance = 0.30;
        private int minInterStopPairsPerWindow = 5;
    }

    @Data
    public static class Bootstrap {
        private double serviceTimeMin = 12.0;
        private double interStopTravelMin = 5.0;
        private int minPickupsForRealData = 20;
    }

    @Data
    public static class Shift {
        private int startHour = 7;
        private int endHour = 20;
    }

    @Data
    public static class Da {
        private double targetUtilisation = 0.70;
        private double maxUtilisation = 0.90;
    }

    @Data
    public static class Intraday {
        private double overloadWarningThreshold = 1.5;
        private double overloadCriticalThreshold = 2.0;
        private int warningSustainedMinutes = 15;
        private int criticalSustainedMinutes = 10;
        private int reAlertSuppressionMinutes = 30;
    }
}
