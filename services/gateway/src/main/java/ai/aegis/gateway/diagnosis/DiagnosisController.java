package ai.aegis.gateway.diagnosis;

import ai.aegis.gateway.diagnosis.DiagnosisService.DiagnoseResult;
import ai.aegis.gateway.security.AuthPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/diagnose")
public class DiagnosisController {

    private final DiagnosisService service;

    public DiagnosisController(DiagnosisService service) {
        this.service = service;
    }

    public record DiagnoseRequest(
            @NotEmpty List<String> logs,
            String provider,
            String model
    ) {
    }

    @PostMapping
    public DiagnoseResult diagnose(@AuthenticationPrincipal AuthPrincipal principal,
                                   @RequestBody DiagnoseRequest req, HttpServletRequest http) {
        return service.run(principal, req.logs(), req.provider(), req.model(), clientIp(http));
    }

    private String clientIp(HttpServletRequest http) {
        String forwarded = http.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return http.getRemoteAddr();
    }
}
