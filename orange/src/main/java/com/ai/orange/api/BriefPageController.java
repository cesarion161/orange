package com.ai.orange.api;

import com.ai.orange.brief.Brief;
import com.ai.orange.brief.BriefProperties;
import com.ai.orange.brief.BriefRepository;
import com.ai.orange.brief.BriefText;
import com.ai.orange.config.ApiToken;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The brief's page (project-space spec §7; Phase D): the brief, then the facts it was written
 * from. {@code /brief} is the latest one and wants the API token (the security chain checks it).
 * {@code /brief/{id}} is what a push links to: a tapped link carries no header, so it opens with
 * the brief's own page key for {@code orange.brief.link-ttl}, or with the API token. Every
 * refusal there is the same 404, so a link tells nothing about which briefs exist.
 */
@RestController
public class BriefPageController {

    private static final MediaType HTML = new MediaType("text", "html", StandardCharsets.UTF_8);

    private final BriefRepository briefs;
    private final ApiToken apiToken;
    private final BriefProperties props;

    public BriefPageController(BriefRepository briefs, ApiToken apiToken, BriefProperties props) {
        this.briefs = briefs;
        this.apiToken = apiToken;
        this.props = props;
    }

    /** The latest brief of any kind. */
    @GetMapping(path = "/brief", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> latest() {
        return page(HttpStatus.OK, briefs.latest().map(BriefText::html).orElseGet(BriefText::empty));
    }

    /** One brief, for whoever holds its key ({@code ?k=}) or the API token. */
    @GetMapping(path = "/brief/{id}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> one(@PathVariable String id,
                                      @RequestParam(name = "k", required = false) String key,
                                      HttpServletRequest request) {
        Optional<Brief> brief = uuid(id).flatMap(briefs::findById);
        if (brief.isEmpty() || !(apiToken.allows(request) || opens(key, brief.get()))) {
            return page(HttpStatus.NOT_FOUND, "");
        }
        return page(HttpStatus.OK, BriefText.html(brief.get()));
    }

    /** Whether {@code key} is this brief's page key and the brief is young enough for it. */
    private boolean opens(String key, Brief brief) {
        if (key == null || brief.pageKey() == null || brief.createdAt() == null) return false;
        boolean same = MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8),
                brief.pageKey().getBytes(StandardCharsets.UTF_8));
        return same && brief.createdAt().plus(props.linkTtl()).isAfter(OffsetDateTime.now());
    }

    private static Optional<UUID> uuid(String id) {
        try {
            return Optional.of(UUID.fromString(id));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** The page holds session text and, in its address, a key: nothing caches it, refers from it or runs in it. */
    private static ResponseEntity<String> page(HttpStatus status, String body) {
        return ResponseEntity.status(status)
                .contentType(HTML)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("Referrer-Policy", "no-referrer")
                .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'")
                .header("X-Content-Type-Options", "nosniff")
                .body(body);
    }
}
