param(
    [string]$Kubeconfig = "C:\Users\HP\Documents\jenkins-secret\kubeconfig-phadagent-dev",
    [string]$Namespace = "tify",
    [string]$OpenAiApiKey = $env:OPENAI_API_KEY
)

$ErrorActionPreference = "Stop"

function New-SecureValue {
    $bytes = [byte[]]::new(32)
    [Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    return [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

function Apply-GeneratedSecret {
    param(
        [string]$Name,
        [string[]]$Literals
    )

    $createArgs = @(
        "--kubeconfig", $Kubeconfig,
        "create", "secret", "generic", $Name,
        "--namespace", $Namespace,
        "--dry-run=client", "-o", "yaml"
    ) + $Literals

    & kubectl @createArgs | & kubectl --kubeconfig $Kubeconfig apply -f - | Out-Host
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to apply secret $Name"
    }
}

$namespaceYaml = & kubectl --kubeconfig $Kubeconfig create namespace $Namespace --dry-run=client -o yaml
$namespaceYaml | & kubectl --kubeconfig $Kubeconfig apply -f - | Out-Host
if ($LASTEXITCODE -ne 0) {
    throw "Failed to ensure namespace $Namespace"
}

$secretNames = @(
    "tify-mysql-secret",
    "tify-redis-secret",
    "tify-pgvector-secret",
    "tify-backend-secret"
)
$existingSecretNames = @(
    & kubectl --kubeconfig $Kubeconfig get secret --namespace $Namespace `
        -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}'
) -split "`n" | Where-Object { $_ -in $secretNames }

if ($existingSecretNames.Count -eq $secretNames.Count) {
    Write-Host "Tify secrets already exist; keeping current values."
    exit 0
}
if ($existingSecretNames.Count -gt 0) {
    throw "Some Tify secrets already exist. Resolve the partial secret set before retrying."
}

$mysqlRootPassword = New-SecureValue
$mysqlPassword = New-SecureValue
$redisPassword = New-SecureValue
$pgvectorPassword = New-SecureValue

Apply-GeneratedSecret "tify-mysql-secret" @(
    "--from-literal=MYSQL_ROOT_PASSWORD=$mysqlRootPassword",
    "--from-literal=MYSQL_DATABASE=tify",
    "--from-literal=MYSQL_USER=tify",
    "--from-literal=MYSQL_PASSWORD=$mysqlPassword"
)

Apply-GeneratedSecret "tify-redis-secret" @(
    "--from-literal=REDIS_PASSWORD=$redisPassword"
)

Apply-GeneratedSecret "tify-pgvector-secret" @(
    "--from-literal=POSTGRES_DB=tify",
    "--from-literal=POSTGRES_USER=tify",
    "--from-literal=POSTGRES_PASSWORD=$pgvectorPassword"
)

$backendLiterals = @(
    "--from-literal=DB_PASSWORD=$mysqlPassword",
    "--from-literal=REDIS_PASSWORD=$redisPassword",
    "--from-literal=PGVECTOR_PASSWORD=$pgvectorPassword"
)
if ($OpenAiApiKey) {
    $backendLiterals += "--from-literal=OPENAI_API_KEY=$OpenAiApiKey"
}
Apply-GeneratedSecret "tify-backend-secret" $backendLiterals

Write-Host "Generated and applied Tify secrets without printing secret values."
