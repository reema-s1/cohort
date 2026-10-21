package dev.cohort.server.flag;

import dev.cohort.server.audit.AuditEntry;
import dev.cohort.server.flag.FlagDtos.CreateFlagRequest;
import dev.cohort.server.flag.FlagDtos.FlagResponse;
import dev.cohort.server.flag.FlagDtos.UpdateFlagRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/flags")
public class FlagController {

    /** Who made the change, for the audit log. A real deployment would take it from the auth token. */
    static final String ACTOR_HEADER = "X-Actor";

    private final FlagService service;

    public FlagController(FlagService service) {
        this.service = service;
    }

    @GetMapping
    public List<FlagResponse> list(@RequestParam(defaultValue = "false") boolean includeArchived) {
        return service.list(includeArchived);
    }

    @GetMapping("/{key}")
    public FlagResponse get(@PathVariable String key) {
        return service.get(key);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FlagResponse create(@Valid @RequestBody CreateFlagRequest request,
                               @RequestHeader(value = ACTOR_HEADER, defaultValue = "admin") String actor) {
        return service.create(request, actor);
    }

    @PutMapping("/{key}")
    public FlagResponse update(@PathVariable String key, @Valid @RequestBody UpdateFlagRequest request,
                               @RequestHeader(value = ACTOR_HEADER, defaultValue = "admin") String actor) {
        return service.update(key, request, actor);
    }

    @PostMapping("/{key}/kill")
    public FlagResponse kill(@PathVariable String key,
                             @RequestHeader(value = ACTOR_HEADER, defaultValue = "admin") String actor) {
        return service.kill(key, actor);
    }

    @DeleteMapping("/{key}")
    public FlagResponse archive(@PathVariable String key,
                                @RequestHeader(value = ACTOR_HEADER, defaultValue = "admin") String actor) {
        return service.archive(key, actor);
    }

    @GetMapping("/{key}/audit")
    public List<AuditEntry> audit(@PathVariable String key) {
        return service.auditLog(key);
    }
}
