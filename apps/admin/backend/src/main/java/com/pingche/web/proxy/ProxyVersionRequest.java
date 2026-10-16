package com.pingche.web.proxy;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record ProxyVersionRequest(@NotNull @PositiveOrZero Long version) { }
