-- Starter departments for a typical Nigerian public health centre.
-- Runs only when the department table is empty, so it never fights with edits
-- made through the admin screen.
--
-- Demo user accounts are NOT seeded here: passwords must be hashed with PBKDF2 by
-- the application, so DemoDataSeeder creates them through AuthService instead.

INSERT INTO department (name, opens_at, closes_at, daily_capacity) VALUES
    ('General Outpatient', '08:00', '16:00', 60),
    ('Antenatal Care',     '08:00', '14:00', 40),
    ('Immunisation',       '09:00', '15:00', 50),
    ('Pharmacy',           '08:00', '17:00', 80),
    ('Laboratory',         '08:00', '15:00', 45),
    ('Dental',             '09:00', '15:00', 25);
