package io.guidein.github.application;

import io.guidein.github.api.RepositoryMaterialSource;
import io.guidein.github.infrastructure.client.GitHubProviderClient;
import io.guidein.github.infrastructure.client.ProviderFailure;
import io.guidein.jobs.api.FailureCategory;
import tools.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;

/** Immutable Git object acquisition through the existing authenticated, versioned provider client. */
public final class GitHubRepositoryMaterialSource implements RepositoryMaterialSource {
    private final GitHubStore store; private final GitHubProviderClient provider;
    private record Authority(long installation, long generation, long repository) {}
    public GitHubRepositoryMaterialSource(GitHubStore store, GitHubProviderClient provider) { this.store = store; this.provider = provider; }
    private Authority authority(UUID tenant, UUID repository) {
        return store.transaction(tenant, () -> store.jdbc.sql("""
                SELECT i.installation_external_id,i.generation,r.repository_external_id
                FROM github_installation_repositories r
                JOIN github_installations i ON i.tenant_id=r.tenant_id AND i.id=r.installation_id
                JOIN repositories p ON p.tenant_id=r.tenant_id AND p.id=r.repository_id
                WHERE r.tenant_id=:tenant AND r.repository_id=:repository AND r.status='ACTIVE'
                    AND p.status='ACTIVE' AND p.external_id=r.repository_external_id::text
                    AND i.status IN ('ACTIVE','ACCESS_REDUCED')
                ORDER BY i.installation_external_id LIMIT 1 FOR SHARE OF r,i,p
                """).param("tenant", tenant).param("repository", repository)
                .query((rs, n) -> new Authority(rs.getLong(1), rs.getLong(2), rs.getLong(3))).optional()
                .orElseThrow(() -> new Failure(FailureCategory.AUTHORIZATION, null)));
    }
    @Override public void requireAuthority(UUID tenantId, UUID repositoryId) { authority(tenantId, repositoryId); }
    @Override public List<UUID> routedTenants() {
        return store.transaction(null, () -> store.jdbc.sql("SELECT DISTINCT tenant_id FROM github_installation_routes ORDER BY tenant_id").query(UUID.class).list());
    }
    private JsonNode get(UUID tenant, UUID repository, Authority expected, String path, Runnable checkpoint) {
        checkpoint.run();
        if (!authority(tenant, repository).equals(expected)) throw new Failure(FailureCategory.AUTHORIZATION, null);
        JsonNode json = provider.get(expected.installation, expected.generation, path).json();
        if (!authority(tenant, repository).equals(expected)) throw new Failure(FailureCategory.AUTHORIZATION, null);
        checkpoint.run(); return json;
    }
    @Override public Material fetch(UUID tenant, UUID repository, String sha, Bounds bounds, Runnable checkpoint) {
        if (sha == null || !sha.matches("[0-9a-f]{40}")) throw new Failure(FailureCategory.INVALID_INPUT, null);
        try {
            Authority auth = authority(tenant, repository); Instant deadline = Instant.now().plusSeconds(bounds.seconds());
            JsonNode identity = get(tenant, repository, auth, "/repositories/" + auth.repository, checkpoint);
            if (identity.path("id").asLong() != auth.repository) throw new Failure(FailureCategory.AUTHORIZATION, null);
            String owner = identity.path("owner").path("login").asText(""), name = identity.path("name").asText("");
            if (!owner.matches("[A-Za-z0-9_.-]{1,100}") || !name.matches("[A-Za-z0-9_.-]{1,100}")) throw new Failure(FailureCategory.INVALID_INPUT, null);
            String base = "/repos/" + owner + "/" + name + "/git/";
            JsonNode commit = get(tenant, repository, auth, base + "commits/" + sha, checkpoint);
            if (!sha.equals(commit.path("sha").asText())) throw new Failure(FailureCategory.STALE_SOURCE, null);
            String treeSha = commit.path("tree").path("sha").asText("");
            if (!treeSha.matches("[0-9a-f]{40}")) throw new Failure(FailureCategory.STALE_SOURCE, null);
            JsonNode tree = get(tenant, repository, auth, base + "trees/" + treeSha + "?recursive=1", checkpoint);
            if (!treeSha.equals(tree.path("sha").asText()) || !tree.path("tree").isArray()) throw new Failure(FailureCategory.STALE_SOURCE, null);
            var files = new TreeMap<String, byte[]>(); var gaps = new ArrayList<Gap>();
            if (tree.path("truncated").asBoolean()) gaps.add(new Gap("SOURCE_TREE_TRUNCATED", ""));
            var entries = new TreeMap<String, JsonNode>();
            for (JsonNode entry : tree.path("tree")) {
                String path;
                try { path=safeRepositoryPath(entry.path("path").asText("")); }
                catch(IllegalArgumentException rejected){addGap(gaps,"PATH_REJECTED","");continue;}
                if (Arrays.stream(path.split("/")).anyMatch(bounds.excludedDirectories()::contains)) continue;
                if (entry.path("type").asText().equals("tree")) continue;
                if (entries.putIfAbsent(path, entry) != null) throw new Failure(FailureCategory.STALE_SOURCE, null);
            }
            long total = 0; int considered = 0;
            for (var pair : entries.entrySet()) {
                if (++considered > bounds.files()) { addGap(gaps, "SOURCE_FILE_COUNT_LIMIT", ""); break; }
                String path = pair.getKey(); JsonNode entry = pair.getValue(); String mode = entry.path("mode").asText();
                if (!Set.of("100644", "100755").contains(mode) || !entry.path("type").asText().equals("blob")) { addGap(gaps, "SOURCE_NON_REGULAR_FILE", path); continue; }
                long size = entry.path("size").asLong(-1);
                if (size < 0 || size > bounds.fileBytes()) { addGap(gaps, "FILE_BYTE_LIMIT", path); continue; }
                if (files.size() >= bounds.files() || total + size > bounds.totalBytes() || !Instant.now().isBefore(deadline)) {
                    addGap(gaps, "SOURCE_BUDGET_EXCEEDED", ""); break;
                }
                String blobSha = entry.path("sha").asText("");
                if (!blobSha.matches("[0-9a-f]{40}")) throw new Failure(FailureCategory.STALE_SOURCE, null);
                JsonNode blob = get(tenant, repository, auth, base + "blobs/" + blobSha, checkpoint);
                String content = blob.path("content").asText("");
                if (!blobSha.equals(blob.path("sha").asText()) || !blob.path("encoding").asText().equals("base64")
                        || content.length() > (bounds.fileBytes() * 4L / 3 + 100_000)) throw new Failure(FailureCategory.STALE_SOURCE, null);
                byte[] bytes;
                try { bytes = Base64.getDecoder().decode(content.replace("\n", "").replace("\r", "")); }
                catch (IllegalArgumentException ex) { throw new Failure(FailureCategory.STALE_SOURCE, null); }
                if (bytes.length != size || bytes.length > bounds.fileBytes() || !gitBlobSha(bytes).equals(blobSha)) throw new Failure(FailureCategory.STALE_SOURCE, null);
                files.put(path, bytes); total += bytes.length;
            }
            requireAuthority(tenant, repository); return new Material(sha, files, gaps);
        } catch (ProviderFailure failure) { throw new Failure(failure.category(), failure.retryAt()); }
    }
    private void addGap(List<Gap> gaps, String category, String path) {
        if (gaps.size() < 2000) gaps.add(new Gap(category, path));
    }
    private String safeRepositoryPath(String raw) {
        if(raw==null||raw.isEmpty()||raw.length()>4096||raw.startsWith("/")||raw.contains("\\")||raw.contains(":")
                ||raw.codePoints().anyMatch(c->c<32||c==127)||!StandardCharsets.UTF_8.newEncoder().canEncode(raw)
                ||raw.getBytes(StandardCharsets.UTF_8).length>1024)throw new IllegalArgumentException("PATH_REJECTED");
        for(String part:raw.split("/",-1))if(part.isEmpty()||part.equals(".")||part.equals(".."))throw new IllegalArgumentException("PATH_REJECTED");
        return raw;
    }
    private String gitBlobSha(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
