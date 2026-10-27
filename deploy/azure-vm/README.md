# Record Locator on an Azure VM (demo, H2)

Demo setup: one Ubuntu VM, the app as a systemd service, embedded **H2 file database**,
OpenSearch off. Not for production (no PostgreSQL, no TLS, no backups).

## Steps

1. **Create the VM** (your machine, `az login` first). Opens port 8080 only to your IP:
   `bash deploy/azure-vm/create-vm.sh`
2. **Build and copy** (the script prints the exact commands):
   `mvn -DskipTests package`, then `scp` the jar (as `record-locator.jar`) and the three
   files in this folder to the VM.
3. **Set Keycloak** - edit `IDP_ISSUER_URI` in `record-locator-demo.env` *before* step 4, or in
   `/etc/record-locator/record-locator.env` afterwards. It must match the token `iss` claim and
   be reachable from the VM.
4. **Install and start** (on the VM): `sudo bash setup-demo.sh`
5. **Check**: `curl localhost:8080/actuator/health` -> `{"status":"UP"}`;
   `journalctl -u record-locator -f` for logs.

## Without Keycloak (open API, demo only)

Use `record-locator-demo-noauth.env` instead: copy it to the VM as `record-locator-demo.env`
(and replace both `CHANGE_ME` passwords with the same value), then run `setup-demo.sh`.
It runs the `dev` profile, so **nothing is authenticated**: lock port 8080 to your IP and use
dummy data only. To switch back later, install `record-locator-demo.env` and set
`IDP_ISSUER_URI`.

## Good to know

- Authentication is ON. Without a valid Keycloak token every `/fhir/**` call returns 401.
  Do not run the `dev` profile on a public VM: it disables authentication and uses an
  in-memory database that is wiped on every restart.
- Data lives in `/var/lib/record-locator/db.mv.db`. Back it up by stopping the service and
  copying the file. The DB password is generated on first install; do not change it later.
- `_name` search returns nothing (OpenSearch off). Everything else works.
- `Binary/<id>` returns 404 until `RL_CONTENT_URL_ALLOWED_HOSTS` is set.
- Traffic is plain HTTP on 8080. Put nginx or Azure Application Gateway in front for TLS
  before exposing it beyond your own IP.
