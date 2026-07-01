package io.audienceflow.desktop.model;

public record CreateRoomRequest(
        String name,
        String building,
        String floor,
        int capacity
) {
}
