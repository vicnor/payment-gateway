package com.gateway.shared.web.support;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class EchoController {

    @GetMapping("/test/ping")
    String ping() {
        return "pong";
    }
}
