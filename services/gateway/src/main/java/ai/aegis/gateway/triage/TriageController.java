package ai.aegis.gateway.triage;

import ai.aegis.gateway.security.AuthPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * RL-driven incident triage. GET returns the org's incidents ranked by the Q-learning
 * agent's learned priority; POST /train updates the policy from resolved incidents.
 */
@RestController
@RequestMapping("/api/triage")
public class TriageController {

    private final TriageService service;

    public TriageController(TriageService service) {
        this.service = service;
    }

    @GetMapping("/queue")
    public List<Map<String, Object>> queue(@AuthenticationPrincipal AuthPrincipal principal) {
        return service.queue(principal);
    }

    @PostMapping("/train")
    public Map<String, Object> train(@AuthenticationPrincipal AuthPrincipal principal) {
        return service.train(principal);
    }
}
