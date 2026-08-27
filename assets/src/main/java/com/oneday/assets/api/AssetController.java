package com.oneday.assets.api;

import com.oneday.assets.domain.AssetCategory;
import com.oneday.assets.domain.AssetStatus;
import com.oneday.assets.dto.AssetCustodyEventView;
import com.oneday.assets.dto.AssetView;
import com.oneday.assets.dto.ConditionRequest;
import com.oneday.assets.dto.EvidenceUpload;
import com.oneday.assets.dto.IssueRequest;
import com.oneday.assets.dto.RegisterAssetRequest;
import com.oneday.assets.dto.SelectVanRequest;
import com.oneday.assets.dto.TransferRequest;
import com.oneday.assets.service.AssetService;
import com.oneday.auth.security.AuthUserDetails;
import com.oneday.grid.service.GridService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * Asset registry + custody API (M13). Station endpoints are STATION_MANAGER-scoped to the manager's own
 * city (ADMIN may pass {@code cityId} for any city); DA-self endpoints require the caller to be that DA.
 */
@RestController
public class AssetController {

    private final AssetService assets;
    private final GridService gridService;

    public AssetController(AssetService assets, GridService gridService) {
        this.assets = assets;
        this.gridService = gridService;
    }

    // ── Station manager ──────────────────────────────────────────────

    @PostMapping("/assets/photo-upload-urls")
    public List<EvidenceUpload> photoUploadUrls(@RequestParam(defaultValue = "1") int count,
                                                @RequestParam(required = false) UUID cityId,
                                                @AuthenticationPrincipal AuthUserDetails principal) {
        Authz.requireRole(principal, Authz.STATION_MANAGER);
        return assets.presignPhotoUploads(count, resolveCity(principal, cityId));
    }

    @PostMapping("/assets")
    public AssetView register(@Valid @RequestBody RegisterAssetRequest req,
                              @AuthenticationPrincipal AuthUserDetails principal) {
        Authz.requireRole(principal, Authz.STATION_MANAGER);
        UUID cityId = resolveCity(principal, req.cityId());
        return assets.register(req, cityId, Authz.requireUserId(principal));
    }

    @GetMapping("/assets")
    public List<AssetView> list(@RequestParam(required = false) UUID cityId,
                                @RequestParam(required = false) AssetStatus status,
                                @RequestParam(required = false) AssetCategory category,
                                @AuthenticationPrincipal AuthUserDetails principal) {
        Authz.requireRole(principal, Authz.STATION_MANAGER, Authz.SUPERVISOR);
        return assets.listByCity(cityScope(principal, cityId), status, category);
    }

    @GetMapping("/assets/reconciliation")
    public List<AssetView> reconciliation(@RequestParam(required = false) UUID cityId,
                                          @AuthenticationPrincipal AuthUserDetails principal) {
        Authz.requireRole(principal, Authz.STATION_MANAGER, Authz.SUPERVISOR);
        return assets.reconciliation(cityScope(principal, cityId));
    }

    @GetMapping("/assets/{id}")
    public AssetView get(@PathVariable UUID id, @AuthenticationPrincipal AuthUserDetails principal) {
        Authz.requireRole(principal, Authz.STATION_MANAGER, Authz.SUPERVISOR);
        return assets.get(id, scope(principal));
    }

    @GetMapping("/assets/{id}/history")
    public List<AssetCustodyEventView> history(@PathVariable UUID id,
                                               @AuthenticationPrincipal AuthUserDetails principal) {
        Authz.requireRole(principal, Authz.STATION_MANAGER, Authz.SUPERVISOR);
        return assets.history(id, scope(principal));
    }

    @PostMapping("/assets/{id}/issue")
    public AssetView issue(@PathVariable UUID id, @Valid @RequestBody IssueRequest req,
                           @AuthenticationPrincipal AuthUserDetails principal) {
        Authz.requireRole(principal, Authz.STATION_MANAGER);
        return assets.issue(id, req.daId(), req.reason(), scope(principal), Authz.requireUserId(principal));
    }

    @PostMapping("/assets/{id}/return")
    public AssetView returnToStation(@PathVariable UUID id,
                                     @RequestBody(required = false) ConditionRequest req,
                                     @AuthenticationPrincipal AuthUserDetails principal) {
        Authz.requireRole(principal, Authz.STATION_MANAGER);
        return assets.returnToStation(id, condition(req), reason(req), scope(principal), Authz.requireUserId(principal));
    }

    @PostMapping("/assets/{id}/transfer")
    public AssetView transfer(@PathVariable UUID id, @Valid @RequestBody TransferRequest req,
                              @AuthenticationPrincipal AuthUserDetails principal) {
        Authz.requireRole(principal, Authz.STATION_MANAGER);
        return assets.transfer(id, req.toDaId(), req.reason(), scope(principal), Authz.requireUserId(principal));
    }

    @PostMapping("/assets/{id}/maintenance")
    public AssetView maintenance(@PathVariable UUID id, @RequestBody(required = false) ConditionRequest req,
                                 @AuthenticationPrincipal AuthUserDetails principal) {
        Authz.requireRole(principal, Authz.STATION_MANAGER);
        return assets.sendToMaintenance(id, reason(req), scope(principal), Authz.requireUserId(principal));
    }

    @PostMapping("/assets/{id}/maintenance/return")
    public AssetView maintenanceReturn(@PathVariable UUID id, @RequestBody(required = false) ConditionRequest req,
                                       @AuthenticationPrincipal AuthUserDetails principal) {
        Authz.requireRole(principal, Authz.STATION_MANAGER);
        return assets.returnFromMaintenance(id, condition(req), reason(req), scope(principal), Authz.requireUserId(principal));
    }

    @PostMapping("/assets/{id}/report-lost")
    public AssetView reportLost(@PathVariable UUID id, @RequestBody(required = false) ConditionRequest req,
                                @AuthenticationPrincipal AuthUserDetails principal) {
        Authz.requireRole(principal, Authz.STATION_MANAGER);
        return assets.reportLost(id, reason(req), scope(principal), Authz.requireUserId(principal));
    }

    @PostMapping("/assets/{id}/report-damaged")
    public AssetView reportDamaged(@PathVariable UUID id, @RequestBody(required = false) ConditionRequest req,
                                   @AuthenticationPrincipal AuthUserDetails principal) {
        Authz.requireRole(principal, Authz.STATION_MANAGER);
        return assets.reportDamaged(id, reason(req), scope(principal), Authz.requireUserId(principal));
    }

    @PostMapping("/assets/{id}/recover")
    public AssetView recover(@PathVariable UUID id, @RequestBody(required = false) ConditionRequest req,
                             @AuthenticationPrincipal AuthUserDetails principal) {
        Authz.requireRole(principal, Authz.STATION_MANAGER);
        return assets.recover(id, reason(req), scope(principal), Authz.requireUserId(principal));
    }

    @PostMapping("/assets/{id}/decommission")
    public AssetView decommission(@PathVariable UUID id, @RequestBody(required = false) ConditionRequest req,
                                  @AuthenticationPrincipal AuthUserDetails principal) {
        Authz.requireRole(principal, Authz.STATION_MANAGER);
        return assets.decommission(id, reason(req), scope(principal), Authz.requireUserId(principal));
    }

    // ── DA-self ──────────────────────────────────────────────────────

    @GetMapping("/assets/da/{daId}/mine")
    public List<AssetView> mine(@PathVariable UUID daId, @AuthenticationPrincipal AuthUserDetails principal) {
        Authz.requireDaSelf(principal, daId);
        return assets.heldBy(daId);
    }

    @GetMapping("/assets/da/{daId}/available-vans")
    public List<AssetView> availableVans(@PathVariable UUID daId,
                                         @AuthenticationPrincipal AuthUserDetails principal) {
        Authz.requireDaSelf(principal, daId);
        return assets.availableVans(principalCity(principal));
    }

    @PostMapping("/assets/da/{daId}/select-van")
    public AssetView selectVan(@PathVariable UUID daId, @RequestBody SelectVanRequest req,
                               @AuthenticationPrincipal AuthUserDetails principal) {
        Authz.requireDaSelf(principal, daId);
        return assets.selectVan(daId, principalCity(principal), req);
    }

    @PostMapping("/assets/da/{daId}/return-van")
    public AssetView returnVan(@PathVariable UUID daId, @AuthenticationPrincipal AuthUserDetails principal) {
        Authz.requireDaSelf(principal, daId);
        return assets.returnVan(daId);
    }

    @PostMapping("/assets/{id}/acknowledge")
    public AssetView acknowledge(@PathVariable UUID id, @AuthenticationPrincipal AuthUserDetails principal) {
        Authz.requireRole(principal, Authz.DELIVERY_ASSOCIATE);
        return assets.acknowledge(id, Authz.requireUserId(principal));
    }

    // ── scope helpers ────────────────────────────────────────────────

    /** The scope city for a single-asset action: null for ADMIN (any city), else the manager's city. */
    private UUID scope(AuthUserDetails principal) {
        return Authz.isAdmin(principal) ? null : principalCity(principal);
    }

    /** The city for a list/read filter: ADMIN must pass one; a manager is pinned to their own. */
    private UUID cityScope(AuthUserDetails principal, UUID requestedCity) {
        if (Authz.isAdmin(principal)) {
            if (requestedCity == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ADMIN must specify cityId");
            }
            return requestedCity;
        }
        return principalCity(principal);
    }

    /** The city an action targets when a body/param may carry one: pin managers, require it for ADMIN. */
    private UUID resolveCity(AuthUserDetails principal, UUID requestedCity) {
        if (Authz.isAdmin(principal)) {
            if (requestedCity == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ADMIN must specify cityId");
            }
            return requestedCity;
        }
        UUID own = principalCity(principal);
        if (requestedCity != null && !requestedCity.equals(own)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot act outside your city");
        }
        return own;
    }

    /** Resolve the principal's own city (users.city_id) to a UUID — the code-or-UUID bridge. */
    private UUID principalCity(AuthUserDetails principal) {
        String city = principal.getUser().getCityId();
        if (city == null || city.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No city assigned to this user");
        }
        try {
            return UUID.fromString(city);
        } catch (IllegalArgumentException notUuid) {
            return gridService.resolveCityId(city);
        }
    }

    private static com.oneday.assets.domain.AssetCondition condition(ConditionRequest req) {
        return req != null ? req.condition() : null;
    }

    private static String reason(ConditionRequest req) {
        return req != null ? req.reason() : null;
    }
}
