# One-time helper: reads backend/.env, keeps only the 4 fields Firebase Admin's cert()
# actually needs (type, project_id, private_key, client_email), and copies the resulting
# compact single-line JSON to the clipboard — ready to paste into the GitHub Secret
# FIREBASE_SERVICE_ACCOUNT_JSON. See auth.js for why only these 4 fields are kept
# (Lambda's 4KB combined env var limit).
$envPath = Join-Path $PSScriptRoot "..\.env"
$envContent = Get-Content $envPath -Raw
$match = [regex]::Match($envContent, '(?m)^FIREBASE_SERVICE_ACCOUNT_JSON=(.+)$')
if (-not $match.Success) {
    Write-Output "Could not find FIREBASE_SERVICE_ACCOUNT_JSON in $envPath"
    exit 1
}
$fullJson = $match.Groups[1].Value
$full = $fullJson | ConvertFrom-Json

$trimmed = [ordered]@{
    type         = $full.type
    project_id   = $full.project_id
    private_key  = $full.private_key
    client_email = $full.client_email
}
$compact = $trimmed | ConvertTo-Json -Compress
Set-Clipboard -Value $compact
Write-Output "Trimmed value copied to clipboard: $($compact.Length) chars (down from $($fullJson.Length))."
Write-Output "Paste it into the GitHub Secret FIREBASE_SERVICE_ACCOUNT_JSON now."
