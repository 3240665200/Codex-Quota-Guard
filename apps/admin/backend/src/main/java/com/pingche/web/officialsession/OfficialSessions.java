package com.pingche.web.officialsession;

import java.util.List;

public record OfficialSessions(List<OfficialSessionInfo> devices, boolean showSessionManager) {
    public OfficialSessions { devices = List.copyOf(devices); }
}
