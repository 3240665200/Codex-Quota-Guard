package com.pingche.web.desktopupdate;

public enum ReleaseComponent {
    DESKTOP, METER;
    public long maximumBytes() { return this==METER ? 64L*1024*1024 : DesktopReleaseService.MAX_PACKAGE_BYTES; }
}
