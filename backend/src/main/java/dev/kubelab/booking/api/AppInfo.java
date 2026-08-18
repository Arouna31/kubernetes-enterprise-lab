package dev.kubelab.booking.api;

public record AppInfo(
        String application,
        String environment,
        String instance,
        String version
) {
}
