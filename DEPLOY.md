# Deploying MediQueue

Three routes, easiest first. All three run the same artifact; only the surroundings differ.

| | Best for | TLS | Cost |
|---|---|---|---|
| [A. Managed platform](#a-managed-platform-render-railway-fly) | your project demo, a marker opening a link | automatic | free tier |
| [B. VPS with Docker](#b-vps-with-docker) | a real health centre pilot | you set it up once | ~$5/month |
| [C. On-site machine](#c-on-site-machine) | a clinic with no reliable internet | local network only | hardware you own |

**Whichever you pick, read [Before you go live](#before-you-go-live) first.** This system
holds patient names, phone numbers and appointment history.

---

## Configuration

Everything is set through environment variables. They override `config.properties`, which
holds development defaults only and never contains a real password.

| Variable | Purpose |
|---|---|
| `DATABASE_URL` | `postgresql://user:pass@host:5432/db` — the form managed databases hand out. Converted to JDBC automatically. |
| `MEDIQUEUE_DB_URL` / `_DB_USER` / `_DB_PASSWORD` | Use these instead if you have the parts separately. |
| `PORT` | Port to listen on. Platforms set this for you; do not hardcode it. |
| `MEDIQUEUE_SECURITY_COOKIE_SECURE` | `true` on anything served over HTTPS. |
| `MEDIQUEUE_ADMIN_EMAIL` / `_ADMIN_PASSWORD` | Creates the first administrator, once, on an empty database. |
| `MEDIQUEUE_DEMO_SEED` | `true` for a demonstration: creates the three README accounts **and shows a "Demonstration system" banner on every page**. Leave unset for a real health centre. |

### Demonstration or real clinic?

Pick one. They are different deployments, not different settings.

**Demonstration** — a marker or classmate should be able to sign in and try all three
roles. Set `MEDIQUEUE_DEMO_SEED=true`. The three README accounts are created and every
page carries a banner saying the system is a demonstration. That banner is the point:
the accounts sharing a published password is fine on throwaway data, and the banner is
what stops anyone entering a real patient's details.

**Real health centre** — leave `MEDIQUEUE_DEMO_SEED` unset and give
`MEDIQUEUE_ADMIN_EMAIL` and `MEDIQUEUE_ADMIN_PASSWORD` instead. One administrator, a
password only you know, no banner, no shared logins.

Any key in `config.properties` can be set this way: lowercase and dots become uppercase
and underscores, with a `MEDIQUEUE_` prefix. `security.pbkdf2.iterations` →
`MEDIQUEUE_SECURITY_PBKDF2_ITERATIONS`.

---

## A. Managed platform (Render, Railway, Fly)

The quickest way to get a URL someone else can open. Render is used here; Railway and Fly
are near-identical.

1. Push this folder to GitHub. **Confirm `lib/postgresql-42.7.4.jar` is committed** —
   `git ls-files lib/` should list it. There is no build tool to fetch the driver, so it
   is a checked-in input; without it the image build fails at `COPY lib/ lib/` with
   `"/lib": not found`.
2. On Render: **New → Postgres**. Create it, then copy the **Internal Database URL**.
3. **New → Web Service**, point it at the repository. Render detects the `Dockerfile`.
4. Add environment variables:

   ```
   DATABASE_URL                      <the internal URL from step 2>
   MEDIQUEUE_ADMIN_EMAIL             you@example.com
   MEDIQUEUE_ADMIN_PASSWORD          <generate one, 20+ characters>
   MEDIQUEUE_SECURITY_COOKIE_SECURE  true
   ```

   Do not set `PORT` — the platform assigns it.
5. Deploy. Open the URL, sign in as the administrator, change the password.

The free tier sleeps after inactivity, so the first request after a quiet period takes
20–30 seconds. Fine for a demo, not for a clinic waiting room.

---

## B. VPS with Docker

A $5 droplet runs this comfortably. `docker-compose.yml` starts the app and its database
together.

```bash
# On the server
git clone <your-repo> mediqueue && cd mediqueue

cp .env.example .env
openssl rand -base64 24        # use for DB_PASSWORD
openssl rand -base64 24        # use for ADMIN_PASSWORD
nano .env                      # fill both in, plus ADMIN_EMAIL

docker compose up -d
docker compose logs -f app     # confirm it started
```

The app binds to `127.0.0.1:8080` and the database publishes no port at all, so neither is
reachable from the internet. Traffic arrives through a reverse proxy, which also handles
TLS.

### TLS with Caddy

Caddy obtains and renews certificates on its own. Create `/etc/caddy/Caddyfile`:

```
mediqueue.example.ng {
    reverse_proxy 127.0.0.1:8080
}
```

```bash
sudo apt install caddy && sudo systemctl reload caddy
```

That is the whole TLS setup. Point the domain's A record at the server first, or the
certificate request fails.

<details>
<summary>Nginx instead of Caddy</summary>

```nginx
server {
    server_name mediqueue.example.ng;
    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

```bash
sudo certbot --nginx -d mediqueue.example.ng
```
</details>

### Updating

```bash
git pull && docker compose up -d --build
```

Data lives in a named volume, so it survives rebuilds. `docker compose down -v` deletes it
— that flag is the one to be careful with.

---

## C. On-site machine

For a health centre with unreliable internet. Everything stays on the local network.

Same `docker compose up -d` as route B, but skip the proxy and set
`MEDIQUEUE_SECURITY_COOKIE_SECURE=false` — without TLS the browser would discard a
`Secure` cookie and nobody could sign in.

Staff reach it at `http://<machine-ip>:8080` over the clinic wifi. Change the app's port
mapping to `"0.0.0.0:8080:8080"` so other machines can connect.

**This is only acceptable on a network outsiders cannot reach**, because passwords cross it
in clear text. If patients are meant to check queue positions on their own phones from
outside the building, you need route A or B.

### Without Docker

```bash
./package.sh          # produces dist/mediqueue.jar, ~1.2 MB
java -jar dist/mediqueue.jar
```

One file, needs only a JRE 17+. Runs on Windows too (`package.bat`). To keep it running
after logout, use a systemd unit or Windows Task Scheduler.

---

## Before you go live

- [ ] **`MEDIQUEUE_DEMO_SEED` is unset.** Those three accounts share a password printed in
      the README. If it is on, every page says "Demonstration system" — if you see that
      banner on a real clinic deployment, stop and turn it off.
- [ ] **Administrator password changed** after first sign-in.
- [ ] **`MEDIQUEUE_SECURITY_COOKIE_SECURE=true`** anywhere TLS is in use. Without it the
      session cookie can travel over plain HTTP.
- [ ] **HTTPS actually works** — open the site and check the padlock. Sign-in credentials
      and patient data cross this connection.
- [ ] **`.env` is not committed.** It is in `.gitignore`; confirm with `git status`.
- [ ] **Database backups.** `docker compose exec db pg_dump -U mediqueue mediqueue > backup.sql`
      on a schedule. Nothing else in this system protects appointment history.

## Known limits

Honest list, so nothing surprises you under load or during a demo:

- **Sessions live in memory.** Restarting signs everyone out, and you cannot run two
  copies behind a load balancer — a request handled by the wrong instance sees no session.
  One instance is ample for a health centre.
- **One database connection per request.** No pooling. Fine at clinic scale; it would need
  HikariCP before serving many sites at once.
- **Queue positions poll every 5 seconds** rather than being pushed.
- **No password reset.** An administrator sets a new password by creating the account
  again, or via SQL.
- **Fonts load from Google Fonts.** With no internet the pages fall back to system faces
  and still work, but for route C consider self-hosting the three families.
