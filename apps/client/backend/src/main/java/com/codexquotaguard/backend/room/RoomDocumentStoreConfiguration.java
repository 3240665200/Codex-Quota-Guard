package com.codexquotaguard.backend.room;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

@Configuration(proxyBeanMethods = false)
public class RoomDocumentStoreConfiguration {

    @Bean
    RoomDocumentStore roomDocumentStore(
            @Value("${quota-guard.room-directory:${user.home}/.codex-quota-guard/room}") String roomDirectory
    ) {
        return new RoomDocumentStore(Path.of(roomDirectory));
    }
}
