package dev.kubelab.booking.api;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class BookingController {

    @Value("${app.environment:local}")
    private String environment;

    @Value("${app.version:1.0.0}")
    private String version;

    @GetMapping("/hotels")
    public List<Hotel> hotels() {
        return List.of(
                new Hotel(1, "Atlas Grand Hotel", "Paris", 18),
                new Hotel(2, "Lake House", "Annecy", 9),
                new Hotel(3, "Urban Lodge", "Lyon", 14),
                new Hotel(4, "Riviera Stay", "Nice", 11),
                new Hotel(5, "Alpine Base", "Grenoble", 7),
                new Hotel(6, "Central Station Hotel", "Lille", 16)
        );
    }

    @GetMapping("/info")
    public AppInfo info() {
        return new AppInfo(
                "booking-api",
                environment,
                hostname(),
                version
        );
    }

    private String hostname() {
        String hostname = System.getenv("HOSTNAME");

        if (hostname != null && !hostname.isBlank()) {
            return hostname;
        }

        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException exception) {
            return "unknown";
        }
    }
}
