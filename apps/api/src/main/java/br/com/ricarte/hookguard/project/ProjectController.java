package br.com.ricarte.hookguard.project;

import br.com.ricarte.hookguard.domain.Project;
import br.com.ricarte.hookguard.web.AccountContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/projects")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@Valid @RequestBody CreateProjectRequest request) {
        UUID accountId = AccountContext.requireAccountId();
        Project project = projectService.create(
                accountId,
                request.name(),
                request.destinationUrl(),
                request.dedupeHeader()
        );
        return toResponse(project, true);
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        UUID accountId = AccountContext.requireAccountId();
        return projectService.list(accountId).stream()
                .map(project -> toResponse(project, false))
                .toList();
    }

    @GetMapping("/{projectId}")
    public Map<String, Object> get(@PathVariable UUID projectId) {
        UUID accountId = AccountContext.requireAccountId();
        return toResponse(projectService.getOwned(accountId, projectId), false);
    }

    @PutMapping("/{projectId}")
    public Map<String, Object> update(
            @PathVariable UUID projectId,
            @RequestBody UpdateProjectRequest request
    ) {
        UUID accountId = AccountContext.requireAccountId();
        Project project = projectService.update(
                accountId,
                projectId,
                request.destinationUrl(),
                request.timeoutMs(),
                request.maxAttempts(),
                request.dedupeHeader()
        );
        return toResponse(project, false);
    }

    @PostMapping("/{projectId}/rotate-key")
    public Map<String, Object> rotateKey(@PathVariable UUID projectId) {
        UUID accountId = AccountContext.requireAccountId();
        Project project = projectService.rotateProjectKey(accountId, projectId);
        return toResponse(project, true);
    }

    private Map<String, Object> toResponse(Project project, boolean includeSecrets) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("id", project.getId().toString());
        body.put("name", project.getName());
        body.put("destinationUrl", project.getDestinationUrl());
        body.put("timeoutMs", project.getTimeoutMs());
        body.put("maxAttempts", project.getMaxAttempts());
        body.put("dedupeHeader", project.getDedupeHeader());
        body.put("status", project.getStatus().toStorage());
        body.put("createdAt", project.getCreatedAt().toString());
        if (includeSecrets) {
            body.put("projectKey", project.getProjectKey());
            body.put("signingSecret", project.getSigningSecret());
            body.put("ingestPath", "/v1/ingest/" + project.getProjectKey());
        }
        return body;
    }

    public record CreateProjectRequest(
            @NotBlank String name,
            @NotBlank String destinationUrl,
            String dedupeHeader
    ) {
    }

    public record UpdateProjectRequest(
            String destinationUrl,
            Integer timeoutMs,
            Integer maxAttempts,
            String dedupeHeader
    ) {
    }
}
