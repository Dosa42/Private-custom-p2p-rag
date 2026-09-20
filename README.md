# Trinity: authenticated MCP, Android RAG and private peer transport

The Android app owns the RAG data and executes tools. A Python service using the
official MCP SDK 2.2.0 exposes Streamable HTTP at `/mcp`, handles OAuth 2.1
authorization code with PKCE S256, and forwards authenticated calls to an enrolled
device over an outbound WebSocket. The phone does not require an inbound Internet
port. Its local MCP endpoint is loopback-only and requires a process-local bearer
credential used by the app's chat client.

## Start the HTTPS service

Use a Linux server with Docker Compose and a DNS hostname pointing to it. Ports 80
and 443 must reach that server. The repository does not provision a hosting account
or DNS domain. From the repository root:

```sh
cp deploy/.env.example deploy/.env
```

Set `TRINITY_DOMAIN` and `ACME_EMAIL` in `deploy/.env`, then:

```sh
docker compose --env-file deploy/.env -f deploy/compose.yaml up -d --build
docker compose --env-file deploy/.env -f deploy/compose.yaml exec gateway trinity-gateway create-user owner
docker compose --env-file deploy/.env -f deploy/compose.yaml exec gateway trinity-gateway create-device owner android-primary
```

`create-user` prompts for your password. `create-device` prints the enrollment token
once. In the Android app's Cloud tab, enter `https://YOUR_DOMAIN` and this device
token, then choose **Connect device**. The linked-account status appears only after
the server validates enrollment. Data created locally is adopted into that account
on the first pairing; later account changes keep each account's records separate.

Add `https://YOUR_DOMAIN/mcp` as the MCP connector in ChatGPT, select OAuth with
dynamic client registration, and sign in using the Trinity account you created.
This login authorizes access to your Trinity data. The separate **Login with ChatGPT**
button inside the APK authenticates its outbound model client.

The gateway publishes its authorization-server discovery document and protected
resource metadata. Client redirects are registered exactly, grants bind the PKCE
challenge, client, redirect and resource, access tokens expire, and refresh tokens
rotate. OAuth scopes map to the read, write and peer-sync tools. See
[gateway documentation](services/trinity_gateway/README.md) for endpoints, CLI,
revocation and protocol details.

Keep the persistent `gateway-data` volume: it contains account identities and hashed
credentials. The service uses one worker because connected-device routing lives in
that process. Multiple enrolled Android devices are supported; tools accept
`device_id` to select one. An unavailable device returns an explicit tool error.

## Actual peer exchange

Pair devices with the same Trinity account. In **Swarm & Tracker**, enter the same
shared swarm key on both devices and start the peer connection. Use at least 32
random characters; for example generate one with `openssl rand -base64 32`. Add the
other device's reachable LAN or VPN address and listening port (default 6881).
Internet NAT traversal is provided by your network/VPN, not by a fabricated peer
connection. The public MCP gateway does not require this peer port to be exposed.

Peers authenticate and encrypt TCP frames using AES-GCM, exchange actual inventory,
download actual pieces, and verify piece and payload SHA-256 before indexing.
The local XOR routing index records verified peers; it does not claim to be a
network-discovering Kademlia implementation. Peer failures and partial downloads are
reported as such. A foreground connection notification keeps the user-enabled
network service running while switching apps; **Disconnect** stops it.

## Chat inside Android

The ChatGPT OAuth session uses the Codex Responses endpoint; API-key mode uses the
public OpenAI Responses endpoint. Models are read from the authenticated provider,
or can be entered explicitly. The app refreshes expiring sessions before requests
and retries a rejected HTTP request after refresh without replaying completed tool
writes. Model function calls execute through authenticated local MCP HTTP.
All returned tool calls are processed, and the Responses loop continues until the
provider returns a completed response. Missing credentials, provider errors and
truncated streams produce errors; there are no generated substitute answers.

## Build and verify

Android requires the configured JDK/Gradle and Android SDK 36.1. The existing GitHub
workflow builds the real APK and runs the JVM/Robolectric regression tests. For a
local debug build, use JDK 21, set `ANDROID_HOME`, and create the existing debug
signing key path if absent:

```sh
keytool -genkeypair -keystore debug.keystore -storepass android -keypass android -alias androiddebugkey -dname 'CN=Android Debug,O=Android,C=US' -keyalg RSA -validity 10000
bash gradlew :app:testDebugUnitTest :app:assembleDebug
```

Do not regenerate an existing debug key when you need APK update compatibility.
Python tests include real localhost OAuth discovery, PKCE, token exchange, official
MCP-client requests and WebSocket relay. Android tests exercise real local sockets,
Unicode HTTP framing, owner persistence, peer transfer/integrity and Responses SSE.
They do not stand in for signing in to a deployed connector on your own phone.

```sh
python3.12 -m venv .venv
.venv/bin/pip install -r services/trinity_gateway/requirements.lock -r services/trinity_gateway/requirements-test.lock
.venv/bin/pip install --no-deps -e services/trinity_gateway
.venv/bin/pytest services/trinity_gateway/tests -q
```
