package com.gateway.acquirer.api;

import com.gateway.acquirer.api.dto.AuthorizeRequest;
import com.gateway.acquirer.api.dto.AuthorizeResponse;
import com.gateway.acquirer.api.dto.LoggedRequest;
import com.gateway.acquirer.domain.AcquirerService;
import com.gateway.acquirer.domain.AuthorizeResult;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AcquirerController {

    private final AcquirerService acquirerService;

    public AcquirerController(AcquirerService acquirerService) {
        this.acquirerService = acquirerService;
    }

    @PostMapping("/internal/v1/authorize")
    public ResponseEntity<AuthorizeResponse> authorize(
            @Valid @RequestBody AuthorizeRequest request) {
        AuthorizeResult result = acquirerService.authorize(request);
        return ResponseEntity.status(result.httpStatus()).body(result.response());
    }

    @GetMapping("/internal/v1/requests")
    public List<LoggedRequest> requests() {
        return acquirerService.getLog();
    }
}
