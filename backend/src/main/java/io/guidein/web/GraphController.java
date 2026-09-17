package io.guidein.web;

import io.guidein.graph.api.*;
import io.guidein.identity.api.*;
import io.guidein.platform.api.RequestIdentity;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/graph")
@ConditionalOnProperty(name="guidein.graph.enabled",havingValue="true")
final class GraphController {
    private final SystemGraph graph;private final IdentityResolver identities;
    GraphController(SystemGraph graph,IdentityResolver identities){this.graph=graph;this.identities=identities;}
    record BuildRequest(@NotNull UUID tenantId,@NotNull UUID repositoryId,@NotNull @Pattern(regexp="[0-9a-f]{40}") String sourceSha){}
    @PostMapping("/snapshots")
    SystemGraph.Snapshot build(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody BuildRequest input,HttpServletRequest request){
        RequestIdentity identity=(RequestIdentity)request.getAttribute(RequestIdentity.class.getName());
        return graph.request(subject(jwt),input.tenantId(),input.repositoryId(),input.sourceSha(),identity.correlationId());
    }
    @GetMapping("/snapshots/{id}")
    SystemGraph.Snapshot snapshot(@AuthenticationPrincipal Jwt jwt,@RequestParam UUID tenantId,@PathVariable UUID id){return graph.snapshot(subject(jwt),tenantId,id);}
    @GetMapping("/snapshots/{id}/nodes")
    SystemGraph.Page<SystemGraph.NodeView> nodes(@AuthenticationPrincipal Jwt jwt,@RequestParam UUID tenantId,@PathVariable UUID id,
            @RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="100") int limit){return graph.nodes(subject(jwt),tenantId,id,offset,limit);}
    @GetMapping("/snapshots/{id}/edges")
    SystemGraph.Page<SystemGraph.EdgeView> edges(@AuthenticationPrincipal Jwt jwt,@RequestParam UUID tenantId,@PathVariable UUID id,
            @RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="100") int limit){return graph.edges(subject(jwt),tenantId,id,offset,limit);}
    @GetMapping("/snapshots/{id}/gaps")
    SystemGraph.Page<SystemGraph.GapView> gaps(@AuthenticationPrincipal Jwt jwt,@RequestParam UUID tenantId,@PathVariable UUID id,
            @RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="100") int limit){return graph.gaps(subject(jwt),tenantId,id,offset,limit);}
    @GetMapping("/nodes/{id}")
    SystemGraph.NodeView node(@AuthenticationPrincipal Jwt jwt,@RequestParam UUID tenantId,@PathVariable UUID id){return graph.node(subject(jwt),tenantId,id);}
    @GetMapping("/traverse")
    SystemGraph.Traversal traverse(@AuthenticationPrincipal Jwt jwt,@RequestParam UUID tenantId,@RequestParam UUID snapshotId,@RequestParam UUID startNodeId,
            @RequestParam(defaultValue="false") boolean reverse,@RequestParam(required=false) Set<GraphModel.EdgeType> edgeTypes,
            @RequestParam(defaultValue="2") int maxDepth,@RequestParam(defaultValue="200") int maxNodes){
        return graph.traverse(subject(jwt),tenantId,snapshotId,startNodeId,reverse,edgeTypes,maxDepth,maxNodes);
    }
    @GetMapping("/diff")
    SystemGraph.Diff diff(@AuthenticationPrincipal Jwt jwt,@RequestParam UUID tenantId,@RequestParam UUID before,@RequestParam UUID after){return graph.diff(subject(jwt),tenantId,before,after);}
    private AuthenticatedSubject subject(Jwt jwt){return identities.resolve(jwt.getIssuer()==null?null:jwt.getIssuer().toString(),jwt.getSubject(),jwt.getClaimAsString("email"),jwt.getClaimAsString("name"));}
}
