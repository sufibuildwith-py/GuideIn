package io.guidein.authorization.api;

public enum Capability {
    TENANT_READ("tenant.read"),
    TENANT_MEMBERS_READ("tenant.members.read"),
    TENANT_MEMBERS_WRITE("tenant.members.write"),
    REPOSITORY_READ("repository.read"),
    REPOSITORY_MANAGE("repository.manage"),
    AUDIT_READ("audit.read"),
    PLATFORM_READ("platform.read");

    private final String id;

    Capability(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }
}

