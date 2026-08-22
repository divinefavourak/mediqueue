# MediQueue

A web-based patient queue and appointment management system for public health centres.
Implementation of the specification in `../Project.md`.

Built with **plain Java (JDK 17+) and PostgreSQL** — no Maven, no Gradle, no frameworks.
The only dependency is the PostgreSQL JDBC driver, a single JAR in `lib/`.

---

## Running it

**1. Start PostgreSQL** (Docker is easiest):

```bash
docker run -d --name mediqueue-db \
  -e POSTGRES_PASSWORD=postgres -e POSTGRES_DB=mediqueue \
  -p 5432:5432 postgres:16
```

Already created it once? `docker start mediqueue-db`.

Using a local PostgreSQL install instead? Create a database called `mediqueue` and put
your credentials in `src/main/resources/config.properties`.

**2. Build and run:**

```
build.bat
run.bat
```

Then open <http://localhost:8080>.

The schema is created automatically on first start, along with six departments and three
demo accounts.

### Demo data

First boot on an empty database seeds a working clinic, so nothing is blank:

- **16 patients** with a fortnight of history — about 520 finished appointments across all
  six departments, with a realistic scatter of no-shows and cancellations so the reports
  have something to describe.
- **Today**: every department has patients already seen, one in the consulting room, and
  several waiting. The `attended_at` timestamps are spaced by a per-department pace, which
  is what makes the wait estimate appear rather than staying hidden.
- **The next five weekdays** of upcoming bookings.

Seeding is deterministic, so the same clinic comes back every time. It takes about 30
seconds on the first boot (hashing 16 passwords with PBKDF2) and is skipped entirely
afterwards — later restarts take about 4 seconds.

`reset-demo.bat` wipes and rebuilds it when the demo gets into a strange state.

### Demo accounts

All use the password `mediqueue123`. The seeded patients use it too, so you can sign in
as anyone in the queue.

| Role | Email | Can do |
|---|---|---|
| Patient | `patient@mediqueue.ng` | Book, view queue position, cancel |
| Staff | `nurse@mediqueue.ng` | Run the queue, attend and skip patients |
| Administrator | `admin@mediqueue.ng` | Manage departments, staff and reports |

### See the queue working

1. Sign in as **patient@mediqueue.ng**, book General Outpatient for today.
2. In a **private window**, register a second patient and book the same department and day.
3. The second patient's dashboard shows **position 2**.
4. In a third window, sign in as **nurse@mediqueue.ng**, open the queue board, mark the
   first patient **Attended**.
5. Within 5 seconds and with no page refresh, patient 2 reads **position 1**.

---

## How it is put together

```
src/main/java/ng/unilag/mediqueue/
  MediQueueApplication.java   entry point
  config/    AppConfig, ServiceRegistry (hand-written DI), DemoDataSeeder
  db/        Database (connections, transactions), SchemaInitializer
  model/     User -> Patient | Staff | Administrator, Department,
             Appointment, AppointmentStatus, QueuePosition
  repository/  *Repository interfaces + Jdbc* implementations
  service/   AuthService, AppointmentService, QueueService,
             DepartmentService, ReportService, PasswordEncoder
  web/       WebServer (routing), handler/*, support/*
src/main/resources/
  schema.sql, seed.sql, config.properties
  static/    the HTML, CSS and JavaScript
```

Layering is strict, and requests always flow one way:

```
Browser -> Handler -> Service -> Repository (interface) -> JDBC -> PostgreSQL
```

A handler never touches SQL; a service never touches HTTP.

### The interface

The visual language comes from the room the system lives in, not from a UI kit. A health
centre has a desk covered in **manila folders** and an **amber board on the wall**, so the
app is manila and stamp ink throughout, and the live queue position is the one dark,
glowing object. Patients already know how to read a now-serving board from across a hall.

| Token | | |
|---|---|---|
| `--manila` | `#E9DBBC` | folder stock — the page ground |
| `--ink` | `#161A16` | stamp ink, body text |
| `--board` | `#0E1F19` | the wall board |
| `--amber` | `#FFB627` | board digits |
| `--stamp` | `#A2322A` | cancelled / absent only |
| `--sage` | `#4A6B54` | attended |

Each typeface is chosen for a reason: **Archivo** for display (drawn for signage and
official documents), **Public Sans** for body (drawn for government public-service
interfaces), **DM Mono** for tickets and times. Ticket numbers are a genuine sequence, so
monospace numerals do the structural work — there is no decorative `01 / 02 / 03`.

**The stub strip** is the one element to remember. Instead of an abstract progress bar,
the queue draws as a row of perforated ticket stubs — one per waiting patient, yours lit
amber. You read your place without reading a number, which is the point when you are
standing up holding a phone. Above 40 waiting it stops being countable and degrades to
three proportional blocks rather than showing a strip nobody can read.

**Motion appears in exactly one place.** The position digit physically flips, and only
when the value actually changes — the moment a patient moves up is the moment they need
to notice. Flipping on every 5-second poll would make it wallpaper. `prefers-reduced-motion`
disables it.

### Design decisions worth knowing

**Queue position is never stored.** It is computed from the ticket number and which
lower tickets are still waiting:

```sql
SELECT COUNT(*) + 1 FROM appointment
 WHERE department_id = ? AND appointment_date = ?
   AND queue_number < ? AND status IN ('BOOKED','WAITING');
```

Storing it would mean rewriting every row behind a patient each time one was attended,
and a crash midway would leave the queue lying to people. Deriving it is one indexed
count and is always correct — which is what makes "positions update automatically"
(§4.3) true rather than aspirational.

**Booking is race-safe.** Two patients booking the same day at the same moment could
otherwise both take ticket 8. `reserveNextQueueNumber` locks the department row with
`SELECT ... FOR UPDATE` *before* reading the highest ticket, so the second transaction
blocks until the first commits. The `UNIQUE (department_id, appointment_date,
queue_number)` constraint is the backstop if that lock is ever bypassed.

*(Note: `SELECT MAX(...) ... FOR UPDATE` does not work — PostgreSQL rejects `FOR UPDATE`
with an aggregate, because `MAX()` leaves no row to lock. Hence locking the department
row, which always exists.)*

**Three tables cover the seven entities in §8.**

| §8 entity | Implementation |
|---|---|
| Patient / Staff / Administrator | `app_user` with a `role` discriminator, mirroring the Java class hierarchy |
| Department, Appointment | tables of their own |
| Queue | derived — a view over today's appointments |
| Report | derived — aggregate queries |

**Passwords use PBKDF2-HMAC-SHA256**, 120,000 iterations, with a random per-user salt —
all from the JDK, no BCrypt dependency. Deliberately slow, so a stolen database resists
brute force. Verification is constant-time via `MessageDigest.isEqual`.

**Sessions** are random 256-bit tokens in an HttpOnly, SameSite=Strict cookie, held in
memory. A restart signs everyone out; appointments, of course, survive.

---

## Verified behaviour

Confirmed against a live PostgreSQL instance:

- Registration, login, duplicate-email and weak-password rejection
- Sequential ticket numbers; **10 simultaneous bookings produced tickets 1–10 with no
  duplicates and no gaps**
- Queue position advancing live as staff mark patients attended
- A patient requesting another patient's appointment gets **403**, not their data
- Path traversal (`/../../config.properties`) blocked
- 20 accounts sharing a password stored as 20 distinct hashes (salting works)
- All four reports from §4.4

---

## Not yet built

Honest list of what the specification asks for that this version does not do:

- **Estimated waiting time** — §4.3 explicitly defers this until queue data exists.
- **Real-time push.** Positions poll every 5 seconds rather than streaming. Server-Sent
  Events is the natural upgrade; `HttpServer` supports it by holding the response open.
- **Password change / reset.** Administrators set a temporary password; there is no
  self-service reset.
- **HTTPS.** Runs over plain HTTP for local development. A real deployment must
  terminate TLS at a reverse proxy, and the session cookie should then be `Secure`.
- **Connection pooling.** One connection per request. Fine at clinic scale; HikariCP
  arrives free with the Spring Boot port.

---

## Porting to Spring Boot

§6.2 specifies Spring Boot. The layering here was chosen so that migration is mechanical
rather than a rewrite:

| Today | After the port |
|---|---|
| `ServiceRegistry` | **deleted** — Spring's container does the wiring |
| `repository/*Repository` interfaces | `extends JpaRepository<T, Long>` |
| `repository/Jdbc*Repository` | **deleted** — Spring Data generates them |
| `service/*` | **unchanged**, plus `@Service` |
| `model/*` | plus `@Entity`, `@Inheritance(SINGLE_TABLE)` |
| `web/handler/*` | become `@RestController` |
| `web/support/Json`, `HttpExchanges` | **deleted** — Jackson and `@RequestParam` |
| `db/Database` | **deleted** — Boot's DataSource, `@Transactional` |
| `static/` | **unchanged** — already at Boot's expected path |

The test of whether this stays true: **nothing in `service/` may import `java.sql`.**
That package is the business logic, and it should not know how anything is stored.
(Verified: currently zero matches.)

One honest caveat. `AppointmentService.book` and `reschedule` receive a transaction
handle from `Database.inTransaction(connection -> ...)` and pass it to the repository.
The type is inferred, never named or imported, and the service does nothing with it
except hand it back — but it *is* a contained abstraction leak. It exists because
reserving a ticket number and inserting the appointment must share one transaction. Under
Spring Boot the parameter disappears entirely: `@Transactional` on the method carries the
transaction implicitly, and those two methods lose the lambda.
