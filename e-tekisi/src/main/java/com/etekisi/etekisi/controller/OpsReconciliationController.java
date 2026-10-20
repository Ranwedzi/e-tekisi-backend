package com.etekisi.etekisi.controller;

import com.etekisi.etekisi.model.ReconciliationRun;
import com.etekisi.etekisi.security.CurrentUser;
import com.etekisi.etekisi.service.ReconciliationService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Money reconciliation for operators (OPERATOR/ADMIN only, via SecurityConfig's /api/ops/** rule). */
@RestController
@RequestMapping("/api/ops/reconciliation")
public class OpsReconciliationController {

    private final ReconciliationService reconciliation;
    private final CurrentUser currentUser;

    public OpsReconciliationController(ReconciliationService reconciliation, CurrentUser currentUser) {
        this.reconciliation = reconciliation;
        this.currentUser = currentUser;
    }

    /** The latest finished run and its findings; {@code {run: null}} before the first run. */
    @GetMapping
    public Map<String, Object> latest() {
        return reconciliation.latest()
                .<Map<String, Object>>map(r -> Map.of("run", r.run(), "findings", r.findings()))
                .orElseGet(() -> java.util.Collections.singletonMap("run", null));
    }

    @GetMapping("/runs")
    public List<ReconciliationRun> runs() {
        return reconciliation.recent();
    }

    /** Runs the checks now (they also run daily at 04:30). */
    @PostMapping("/run")
    public Map<String, Object> runNow() {
        ReconciliationService.Report r = reconciliation.run("MANUAL:" + currentUser.id());
        return Map.of("run", r.run(), "findings", r.findings());
    }
}
