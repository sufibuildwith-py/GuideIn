package io.guidein.change.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ChangeCandidate(UUID tenantId,UUID repositoryId,String provider,String providerChangeId,
                              String changeType,String baseSha,String headSha,String ref,boolean forced,
                              String title,String state,Integer reportedFileCount,String fileSetStatus,
                              String fileSetReason,Instant providerUpdatedAt,List<FileMetadata> files) {
    public ChangeCandidate {
        files=List.copyOf(files);
        if(tenantId==null || repositoryId==null || headSha==null || !headSha.matches("[0-9a-f]{40}|[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid change identity");
        if("COMPLETE".equals(fileSetStatus) && (reportedFileCount==null || reportedFileCount!=files.size()))
            throw new IllegalArgumentException("Incomplete file set cannot be marked complete");
    }
    public record FileMetadata(String path,String previousPath,String status,Integer additions,Integer deletions,
                               Integer changes,String blobAfterSha,String blobBeforeSha,String language) { }
}
