package io.guidein.github.application;

import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import io.guidein.platform.api.*;

/** A deliberately narrow, untrusted selector; never a normalized provider fact. */
record GitHubNotification(long installation,long repository,String event,String action,Map<String,Object> selectors,boolean supported) {
    private static final Map<String,Set<String>> ACTIONS=Map.of(
            "pull_request",Set.of("opened","reopened","synchronize","closed","edited","ready_for_review","converted_to_draft"),
            "push",Set.of(""),"status",Set.of(""),"check_run",Set.of("created","rerequested","completed","requested_action"),
            "installation",Set.of("created","deleted","suspend","unsuspend","new_permissions_accepted"),
            "installation_repositories",Set.of("added","removed"),"installation_target",Set.of("renamed"));
    static GitHubNotification parse(byte[] raw,String event) {
        try {
            var factory=tools.jackson.core.json.JsonFactory.builder().streamReadConstraints(tools.jackson.core.StreamReadConstraints.builder()
                    .maxNestingDepth(100).maxStringLength(1_000_000).maxNumberLength(100).build()).build();
            JsonNode json=JsonMapper.builder(factory).build().readTree(raw);
            if(json==null || !json.isObject() || event==null || !event.matches("[a-z_]{1,80}")) throw new IllegalArgumentException();
            String action=json.path("action").asText("");
            if(!action.matches("[a-z_]{0,80}")) throw new IllegalArgumentException();
            long installation=json.path("installation").path("id").asLong(0),repo=json.path("repository").path("id").asLong(0);
            boolean supported=ACTIONS.getOrDefault(event,Set.of()).contains(action);
            Map<String,Object> selectors=new LinkedHashMap<>();
            if(supported && installation<1) throw new IllegalArgumentException();
            if(supported && event.equals("pull_request")) {
                long number=json.path("number").asLong(json.path("pull_request").path("number").asLong(0));
                if(number<1 || repo<1) throw new IllegalArgumentException();
                selectors.put("number",number);
            }
            if(supported && event.equals("push")) {
                String ref=json.path("ref").asText();
                if(ref.length()>1024 || !ref.startsWith("refs/") || repo<1) throw new IllegalArgumentException();
                selectors.put("ref",ref);selectors.put("before",sha(json.path("before").asText()));
                selectors.put("after",sha(json.path("after").asText()));selectors.put("forced",json.path("forced").asBoolean());
                selectors.put("deleted",json.path("deleted").asBoolean());
            }
            if(supported && (event.equals("status") || event.equals("check_run"))) {
                if(repo<1) throw new IllegalArgumentException();
                selectors.put("sha",sha(event.equals("status")?json.path("sha").asText():json.path("check_run").path("head_sha").asText()));
            }
            return new GitHubNotification(installation,repo,event,action,Map.copyOf(selectors),supported);
        } catch(RuntimeException ignored) { throw new GuideInException(ErrorCode.VALIDATION_FAILED); }
    }
    static String sha(String sha) {
        if(sha==null || !sha.matches("[0-9a-f]{40}|[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid source digest");
        return sha;
    }
}
