package com.pingche.web.sessionbinding;

import java.util.UUID;

public record ClientAccessResponse(boolean allowed, UUID bindingId, String reason) { }
