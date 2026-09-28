package ai.aegis.gateway.admin;

import ai.aegis.gateway.common.ApiException;
import ai.aegis.gateway.security.AuthPrincipal;
import jakarta.validation.constraints.NotBlank;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Hardened SQL runner — SUPER_ADMIN only (platform owner). Read-only by default:
 * SELECT/EXPLAIN/WITH allowed; a single statement only; dangerous keywords blocked.
 * The schema endpoint validates the table name against information_schema (bound param)
 * to eliminate the injection the original app had.
 */
@RestController
@RequestMapping("/api/admin/sql")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class SqlRunnerController {

    private final JdbcTemplate jdbc;

    public SqlRunnerController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record SqlRequest(@NotBlank String query) {
    }

    @GetMapping("/tables")
    public List<String> tables() {
        return jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' ORDER BY table_name",
                String.class);
    }

    @GetMapping("/schema/{table}")
    public List<Map<String, Object>> schema(@PathVariable String table) {
        // Validate the table exists (bound param) — no string interpolation into SQL.
        Integer exists = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name = ?",
                Integer.class, table);
        if (exists == null || exists == 0) {
            throw ApiException.badRequest("Unknown table");
        }
        return jdbc.queryForList(
                "SELECT column_name, data_type, is_nullable FROM information_schema.columns "
                        + "WHERE table_schema='public' AND table_name = ? ORDER BY ordinal_position",
                table);
    }

    @PostMapping("/execute")
    public Map<String, Object> execute(@AuthenticationPrincipal AuthPrincipal principal,
                                       @RequestBody SqlRequest req) {
        String query = req.query().trim().replaceAll(";+$", "");
        String lower = query.toLowerCase();

        if (query.contains(";")) {
            throw ApiException.badRequest("Only a single statement is allowed");
        }
        boolean readOnly = lower.startsWith("select") || lower.startsWith("explain") || lower.startsWith("with");
        if (!readOnly) {
            throw ApiException.badRequest("Only read-only queries (SELECT/EXPLAIN/WITH) are permitted");
        }
        for (String banned : List.of(" insert ", " update ", " delete ", " drop ", " alter ",
                " create ", " truncate ", " grant ", " revoke ", "pg_catalog", "pg_read")) {
            if (lower.contains(banned)) {
                throw ApiException.badRequest("Query contains a disallowed keyword");
            }
        }

        List<Map<String, Object>> rows = jdbc.queryForList(query + " LIMIT 200");
        List<String> columns = rows.isEmpty() ? List.of() : List.copyOf(rows.get(0).keySet());
        return Map.of("columns", columns, "rows", rows, "rowCount", rows.size());
    }
}
