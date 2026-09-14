param([Parameter(Mandatory=$true)][string]$ProofCommit)
$ErrorActionPreference = 'Stop'
if ($ProofCommit -notmatch '^[0-9a-f]{40}$') { throw 'Provide the full tested proof commit SHA.' }
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Set-Location $projectRoot
$individuals = @()
$source = Get-Content 'backend/src/test/java/io/guidein/integration/PlatformKernelIntegrationIT.java' -Raw
$methods = [regex]::Matches($source, '@Test\s+void\s+(\w+)') | ForEach-Object { $_.Groups[1].Value }
foreach ($method in $methods) {
    [xml]$xml = Get-Content "evaluation/individual-proof/$method.xml"
    $case = @($xml.testsuite.testcase) | Where-Object { ($_.name -split '\(', 2)[0] -eq $method }
    if (-not $case -or $case.failure -or $case.error -or $case.skipped) { throw "Individual proof failed or absent: $method" }
    $individuals += [ordered]@{test=$method;status='PASS';duration_seconds=[double]$case.time;failure_reason=$null}
}
$allCases = @()
foreach ($file in Get-ChildItem 'backend/target/surefire-reports/TEST-*.xml','backend/target/failsafe-reports/TEST-*.xml') {
    [xml]$xml = Get-Content -LiteralPath $file.FullName
    foreach ($case in $xml.testsuite.testcase) {
        if ($case.failure -or $case.error -or $case.skipped) { throw "Full-suite proof not green: $($case.name)" }
        $allCases += [ordered]@{class=$case.classname;test=$case.name;duration_seconds=[double]$case.time;status='PASS'}
    }
}
if ($allCases.Count -lt 70) { throw 'Incomplete full-suite evidence.' }
$measurements = [ordered]@{}
foreach ($name in @('environment','tenant-reads','tenant-writes','authorization','missing-context','pool','runtime-role','audit','audit-concurrency','atomicity','outbox-recovery','concurrency','leases','availability','observability','transaction-metrics','performance')) {
    $measurements[$name] = Get-Content "backend/target/proof/$name.json" -Raw | ConvertFrom-Json
}
$gates = [ordered]@{
    cross_tenant_reads=$measurements['tenant-reads'].cross_tenant_reads
    cross_tenant_writes=$measurements['tenant-writes'].cross_tenant_writes
    unauthorized_protected_actions=$measurements.authorization.unauthorized_protected_actions
    audit_mutations_runtime_role=$measurements['runtime-role'].audit_mutations_runtime_role
    tenant_context_leaks=$measurements.pool.tenant_context_leaks
    missing_tenant_context_exposures=$measurements['missing-context'].missing_tenant_context_exposures
    outbox_atomicity_violations=$measurements.atomicity.outbox_atomicity_violations
    duplicate_job_effects=$measurements.concurrency.duplicate_job_effects
    lost_jobs=$measurements.concurrency.lost_jobs
    simultaneous_job_owners=$measurements.concurrency.simultaneous_valid_owners
    undetected_audit_tampering=$measurements.audit.undetected_audit_tampering
    forbidden_schema_operations=$measurements['runtime-role'].forbidden_schema_operations
    secret_leakage=$measurements.observability.secret_leaks
    flyway_validation_errors=$measurements.environment.validation_errors
}
foreach ($entry in $gates.GetEnumerator()) {
    if ($null -eq $entry.Value -or $entry.Value -ne 0) { throw "Unmeasured or nonzero gate: $($entry.Key)" }
}
$result = [ordered]@{
    phase=1;status='PASS';phase_2_readiness='READY';evaluated_at=(Get-Date -Format o)
    implementation_baseline_commit='c8b5cffcf108984163aec689bcc9e695fde65f5e'
    evidence_baseline_commit='0d2a81bd9c21347d8a130f23eed605410b607e07'
    final_proof_commit=$ProofCommit
    history=@([ordered]@{date='2026-09-11';status='FAIL';readiness='NOT_READY';passed=62;skipped=8;reason='PostgreSQL gates not executed'})
    build=[ordered]@{status='PASS';command='mvn --batch-mode --no-transfer-progress clean verify'}
    tests=[ordered]@{total=$allCases.Count;passed=$allCases.Count;failed=0;skipped=0;individual_proof_tests=$individuals.Count}
    security_gates=$gates
    measurements=$measurements
    individual_tests=$individuals
    full_suite=$allCases
    known_limitations=@(
        'OTel agent trace/MDC propagation was measured locally; remote collector delivery was not tested.',
        'Pending/depth gauges are process-local hints, not durable global queue totals.',
        'Audit chain detects tampering relative to its persisted head; external anchoring/signatures remain outside Phase 1.',
        'Outbox delivery is at-least-once; production consumers must implement idempotent effects.',
        'Performance measurements include assertions and local Docker overhead; no production capacity claim.'
    )
}
$result | ConvertTo-Json -Depth 12 | Set-Content 'evaluation/phase1-results.json' -Encoding utf8
Write-Host "Recorded $($allCases.Count)/$($allCases.Count) passing tests and $($individuals.Count) individual proof results."
