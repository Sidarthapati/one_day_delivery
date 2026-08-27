package com.oneday.assets.dto;

import com.oneday.assets.domain.AssetCondition;

/** Optional condition + reason for return / maintenance / damage actions (both nullable). */
public record ConditionRequest(AssetCondition condition, String reason) {}
