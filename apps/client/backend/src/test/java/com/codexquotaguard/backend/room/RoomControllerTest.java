package com.codexquotaguard.backend.room;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RoomControllerTest {

    @Test
    void delegatesOverviewRequestsToTheRoomService() {
        var service = new RoomOverviewService(new RoomDocumentStore(Path.of("target", "missing-room")));

        var response = new RoomController(service).overview();

        assertThat(response.configured()).isFalse();
    }
}
