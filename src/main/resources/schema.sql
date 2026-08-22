-- MediQueue schema (PostgreSQL)
--
-- Three tables implement the seven entities listed in Project.md section 8:
--   Patient / Staff / Administrator -> app_user, separated by the `role` discriminator
--   Department, Appointment        -> tables of their own
--   Queue, Report                  -> DERIVED, deliberately not stored (see below)
--
-- Why Queue is not a table: a queue is a *view* over today's appointments. If a
-- patient's position were a stored column, attending one patient would have to rewrite
-- every row behind them (O(n) writes per action), and a crash midway would leave the
-- queue corrupt. Deriving position from queue_number is one indexed COUNT and is always
-- correct. Report is likewise an aggregate query -- storing it guarantees staleness.
--
-- This file is idempotent: it is safe to run on every startup.

CREATE TABLE IF NOT EXISTS department (
    id             BIGSERIAL PRIMARY KEY,
    name           VARCHAR(100) UNIQUE NOT NULL,
    opens_at       TIME         NOT NULL DEFAULT '08:00',
    closes_at      TIME         NOT NULL DEFAULT '16:00',
    daily_capacity INT          NOT NULL DEFAULT 50 CHECK (daily_capacity > 0),
    active         BOOLEAN      NOT NULL DEFAULT TRUE
);

CREATE TABLE IF NOT EXISTS app_user (
    id            BIGSERIAL PRIMARY KEY,
    role          VARCHAR(20)  NOT NULL CHECK (role IN ('PATIENT', 'STAFF', 'ADMIN')),
    full_name     VARCHAR(120) NOT NULL,
    email         VARCHAR(120) NOT NULL,
    phone         VARCHAR(20),
    password_hash VARCHAR(255) NOT NULL,
    password_salt VARCHAR(64)  NOT NULL,
    -- Staff belong to a department; patients and admins do not.
    department_id BIGINT REFERENCES department (id) ON DELETE SET NULL,
    created_at    TIMESTAMP    NOT NULL DEFAULT now()
);

-- Case-insensitive uniqueness: Ada@x.com and ada@x.com must not be two accounts.
CREATE UNIQUE INDEX IF NOT EXISTS idx_app_user_email ON app_user (LOWER(email));

CREATE TABLE IF NOT EXISTS appointment (
    id               BIGSERIAL PRIMARY KEY,
    patient_id       BIGINT      NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    department_id    BIGINT      NOT NULL REFERENCES department (id),
    appointment_date DATE        NOT NULL,
    queue_number     INT         NOT NULL CHECK (queue_number > 0),
    status           VARCHAR(20) NOT NULL CHECK (status IN
                        ('BOOKED', 'WAITING', 'IN_PROGRESS', 'ATTENDED', 'SKIPPED', 'CANCELLED')),
    booked_at        TIMESTAMP   NOT NULL DEFAULT now(),
    attended_at      TIMESTAMP,
    -- Two patients must never hold the same ticket. This constraint is the real
    -- guard against the booking race condition; the FOR UPDATE lock is the fast path.
    CONSTRAINT uq_queue_slot UNIQUE (department_id, appointment_date, queue_number)
);

-- Serves the position query, the staff queue board, and the daily reports.
CREATE INDEX IF NOT EXISTS idx_appt_queue
    ON appointment (department_id, appointment_date, status, queue_number);

CREATE INDEX IF NOT EXISTS idx_appt_patient
    ON appointment (patient_id, appointment_date DESC);
