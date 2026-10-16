package com.pingche.web.proxy;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record ProxyNodeEnabledRequest(@NotNull Boolean enabled, @NotNull @PositiveOrZero Long version) { }
