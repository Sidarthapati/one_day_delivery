package com.oneday.grid.demo;

import com.oneday.grid.demo.AbsenceDemoService.DemoDa;
import com.oneday.grid.demo.AbsenceDemoService.DemoPlan;
import com.oneday.grid.demo.AbsenceDemoService.DemoState;
import com.oneday.grid.service.GridService;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Demo-only endpoints (not in prod — {@code !prod}) for the midday-absence flood-fill visualiser at
 * {@code /absence-demo.html}. Seed a city's territories, read the current split + geometry, and run
 * the real reassignment for any absent set.
 */
@RestController
@Profile("!prod")
public class AbsenceDemoController {

    private final AbsenceDemoService demo;
    private final GridService gridService;

    public AbsenceDemoController(AbsenceDemoService demo, GridService gridService) {
        this.demo = demo;
        this.gridService = gridService;
    }

    /** Partition a city into {@code das} contiguous synthetic DA territories for today. */
    @PostMapping("/api/grid/demo/absence/seed")
    public List<DemoDa> seed(@RequestParam String city, @RequestParam(defaultValue = "6") int das) {
        return demo.seed(resolveCity(city), das);
    }

    /** Current territories (DA per hex) + hex polygons, for the map. */
    @GetMapping("/api/grid/demo/absence/state")
    public DemoState state(@RequestParam String city) {
        return demo.state(resolveCity(city));
    }

    /** Run the real reassignment for a comma-separated absent set — returns per-hex moves + orphans. */
    @GetMapping("/api/grid/demo/absence/plan")
    public DemoPlan plan(@RequestParam String city, @RequestParam(required = false) String absent) {
        List<UUID> absentIds = (absent == null || absent.isBlank()) ? List.of()
                : Arrays.stream(absent.split(",")).map(String::trim).filter(s -> !s.isBlank())
                        .map(UUID::fromString).toList();
        return demo.plan(resolveCity(city), absentIds);
    }

    /** Accepts a city UUID or a grid.cities code (e.g. "delhi"). */
    private UUID resolveCity(String city) {
        try {
            return UUID.fromString(city);
        } catch (IllegalArgumentException notUuid) {
            return gridService.resolveCityId(city);
        }
    }
}
