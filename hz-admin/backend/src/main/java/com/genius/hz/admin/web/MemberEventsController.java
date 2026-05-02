package com.genius.hz.admin.web;

import com.genius.hz.admin.sse.SseHub;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Server-Sent Events stream of cluster membership changes.
 * Replaces the 5-second polling on the Members page.
 */
@RestController
@RequestMapping("/api/clusters/{id}/members/events")
@Tag(name = "MemberEvents", description = "SSE stream of MEMBER_ADDED / MEMBER_REMOVED")
@PreAuthorize("isAuthenticated()")
public class MemberEventsController {

    private final SseHub hub;

    public MemberEventsController(SseHub hub) { this.hub = hub; }

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable("id") Long id) {
        return hub.subscribeMembership(id);
    }
}
