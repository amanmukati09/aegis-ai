package ai.aegis.gateway.analytics;

import ai.aegis.gateway.common.ApiException;
import ai.aegis.gateway.ml.MlClient;
import ai.aegis.gateway.security.AuthPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * NL->SQL analytics. The ML sidecar generates a SELECT; the gateway is the DB owner
 * and enforces safety: SELECT-only, single statement, and — critically — it scopes
 * the query to the caller's org by wrapping it, so a user can never read another
 * org's data even if the model produced an unscoped query.
 */
@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {

    private final MlClient ml;
    private final JdbcTemplate jdbc;

    public AnalyticsController(MlClient ml, JdbcTemplate jdbc) {
        this.ml = ml;
        this.jdbc = jdbc;
    }

    public record AskRequest(@NotBlank String question) {
    }

    private static final List<String> PRESETS = List.of(
            "How many incidents by severity?",
            "How many open vs resolved incidents?",
            "Incidents created per day this week",
            "What are the most recent critical incidents?"
    );

    @GetMapping("/presets")
    public List<String> presets() {
        return PRESETS;
    }

    /** Incidents per day for the last 14 days (org-scoped) — powers the dashboard line chart. */
    @GetMapping("/timeseries")
    public List<Map<String, Object>> timeseries(@AuthenticationPrincipal AuthPrincipal principal) {
        String sql = """
                SELECT to_char(d.day, 'YYYY-MM-DD') AS day,
                       count(i.id) AS total
                FROM generate_series(current_date - interval '13 days', current_date, interval '1 day') AS d(day)
                LEFT JOIN incidents i
                       ON i.org_id = ? AND date_trunc('day', i.detected_at) = d.day
                GROUP BY d.day ORDER BY d.day
                """;
        return jdbc.queryForList(sql, principal.orgId());
    }

    @PostMapping("/ask")
    public Map<String, Object> ask(@AuthenticationPrincipal AuthPrincipal principal,
                                   @Valid @RequestBody AskRequest req) {
        if (principal.orgId() == null) {
            throw ApiException.badRequest("Analytics requires an organization context");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> gen = ml.nlToSql(Map.of("question", req.question()));
        String sql = String.valueOf(gen.getOrDefault("sql", "")).trim().replaceAll(";+$", "");
        // Strip a trailing LIMIT so we can safely append our own cap.
        sql = sql.replaceAll("(?i)\\s+limit\\s+\\d+\\s*$", "");
        String chartType = String.valueOf(gen.getOrDefault("chart_type", "table"));

        validateSelectOnly(sql);

        // Org-scope by shadowing the `incidents` table with a CTE pre-filtered to the
        // caller's org. The generated SELECT reads `FROM incidents`, so it transparently
        // sees only this org's rows — works for aggregates too, and isolation holds even
        // if the model produced an unscoped query. Read-only, hard row cap.
        String scoped = "WITH incidents AS (SELECT * FROM incidents WHERE org_id = ?) "
                + sql + " LIMIT 500";
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(scoped, principal.orgId());
            List<String> columns = rows.isEmpty() ? List.of() : List.copyOf(rows.get(0).keySet());
            return Map.of(
                    "sql", sql,
                    "explanation", gen.getOrDefault("explanation", ""),
                    "chartType", chartType,
                    "columns", columns,
                    "rows", rows
            );
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Could not run that query safely. Try rephrasing. (" + rootMessage(e) + ")");
        }
    }

    private void validateSelectOnly(String sql) {
        String lower = sql.toLowerCase().trim();
        // Must be a bare SELECT (no leading CTE that could redefine our org-scoped `incidents`).
        if (!lower.startsWith("select")) {
            throw ApiException.badRequest("Only a single SELECT query is allowed");
        }
        if (sql.contains(";")) {
            throw ApiException.badRequest("Only a single statement is allowed");
        }
        for (String banned : List.of(" insert ", " update ", " delete ", " drop ", " alter ",
                " create ", " truncate ", " grant ", " revoke ", "pg_", "information_schema")) {
            if (lower.contains(banned)) {
                throw ApiException.badRequest("Query contains a disallowed keyword");
            }
        }
    }

    private String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        String m = t.getMessage();
        return m == null ? "error" : m.split("\n")[0];
    }
}
