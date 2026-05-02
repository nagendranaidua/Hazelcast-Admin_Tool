package com.genius.hz.admin.web;

import com.genius.hz.admin.bridge.BridgeRouter;
import com.genius.hz.api.MemberInfo;
import com.genius.hz.bridge.HazelcastBridge;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/clusters/{clusterId}/members")
@Tag(name = "Members", description = "Cluster member listing & lifecycle")
@PreAuthorize("isAuthenticated()")
public class MemberController {

    private final BridgeRouter router;

    public MemberController(BridgeRouter router) { this.router = router; }

    @GetMapping
    public List<MemberInfo> members(@PathVariable Long clusterId) {
        return router.bridgeFor(clusterId).listMembers();
    }

    @GetMapping("/state")
    public Map<String,Object> state(@PathVariable Long clusterId) {
        HazelcastBridge b = router.bridgeFor(clusterId);
        Map<String,Object> r = new LinkedHashMap<>();
        r.put("clusterName",  b.getClusterName());
        r.put("clusterState", b.getClusterState());
        r.put("memberCount",  b.listMembers().size());
        return r;
    }

    @PostMapping("/state")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','CLUSTER_OPERATOR')")
    public void changeState(@PathVariable Long clusterId, @RequestParam String newState) {
        router.bridgeFor(clusterId).changeClusterState(newState);
    }
}
