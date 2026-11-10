package com.ai.orange.api;

import com.ai.orange.attention.AttentionItem;
import com.ai.orange.attention.AttentionService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The attention inbox: everything that wants the user, and their answers. */
@RestController
@RequestMapping("/attention")
public class AttentionController {

    private final AttentionService attention;

    public AttentionController(AttentionService attention) {
        this.attention = attention;
    }

    /** Items by status ({@code open} by default, {@code all} for every status), most urgent first. */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list(@RequestParam(defaultValue = "open") String status,
                                                          @RequestParam(defaultValue = "50") int limit) {
        if (!List.of("open", "acked", "resolved", "all").contains(status)) return ResponseEntity.badRequest().build();
        return ResponseEntity.ok(attention.list("all".equals(status) ? null : status, limit).stream()
                .map(SpaceViews::of).toList());
    }

    /** "Seen, not now": the item stays in the inbox but is no longer pushed or escalated. */
    @PostMapping("/{id}/ack")
    public ResponseEntity<Map<String, Object>> ack(@PathVariable UUID id) {
        Optional<AttentionItem> item = attention.find(id);
        if (item.isEmpty()) return ResponseEntity.notFound().build();
        attention.ack(id);
        return ResponseEntity.ok(SpaceViews.of(attention.find(id).orElseThrow()));
    }

    /** Body: {@code {"answer": "…"}} (optional except for a proposed project: confirm or ignore). */
    @PostMapping("/{id}/resolve")
    public ResponseEntity<Map<String, Object>> resolve(@PathVariable UUID id,
                                                       @RequestBody(required = false) Map<String, Object> body) {
        String answer = body != null && body.get("answer") instanceof String s ? s : null;
        try {
            return attention.resolve(id, answer, "user")
                    .map(item -> ResponseEntity.ok(SpaceViews.of(item)))
                    .orElseGet(() -> ResponseEntity.notFound().build());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
