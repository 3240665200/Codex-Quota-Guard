package com.codexquotaguard.backend.room;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/room")
public class RoomController {

    private final RoomOverviewService overviewService;

    public RoomController(RoomOverviewService overviewService) {
        this.overviewService = overviewService;
    }

    @GetMapping("/overview")
    public RoomOverview overview() {
        return overviewService.readOverview();
    }
}
