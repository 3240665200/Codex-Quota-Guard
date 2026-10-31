package com.pingche.web.desktopupdate;

import com.pingche.web.auth.DomainException;
import org.springframework.http.HttpStatus;

record DesktopVersion(int major,int minor,int patch) implements Comparable<DesktopVersion> {
    static DesktopVersion parse(String value) {
        if(value==null || !value.matches("(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})"))
            throw new DomainException("DESKTOP_VERSION_INVALID","版本号须为三个非负整数，例如 0.4.12。",HttpStatus.BAD_REQUEST);
        var parts=value.split("\\.");
        return new DesktopVersion(Integer.parseInt(parts[0]),Integer.parseInt(parts[1]),Integer.parseInt(parts[2]));
    }
    @Override public int compareTo(DesktopVersion other) {
        int compared=Integer.compare(major,other.major);
        if(compared==0) compared=Integer.compare(minor,other.minor);
        return compared==0 ? Integer.compare(patch,other.patch) : compared;
    }
}
