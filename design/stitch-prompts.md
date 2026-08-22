# Stitch prompts for redesigning MediQueue

Working notes for exploring a new visual direction. Stitch does its best work one screen
at a time, so start with the app-level prompt, then feed it individual screens.

---

## 1. App-level prompt (paste first)

```
Design a responsive web app called MediQueue — a patient queue and appointment
system for public health centres in Nigeria.

WHO USES IT
- Patients, on cheap Android phones, standing in a crowded brightly-lit waiting
  hall. They open it to answer one question: "how much longer until I'm seen?"
- Nurses, on a desktop at the consulting-room desk, same screen open all day.
- Administrators, managing departments, staff accounts and reports.

THE ONE THING THAT MATTERS
The live queue position. A patient must see their ticket number, how many people
are ahead of them, and who is being seen right now — readable at arm's length, in
sunlight, at a glance. No scrolling, no hunting. Everything else is secondary.

STYLE
[paste one STYLE block]

USE REAL CONTENT, NOT PLACEHOLDER TEXT
- Departments: General Outpatient, Antenatal Care, Immunisation, Pharmacy,
  Laboratory, Dental
- Patients: Chidi Okafor, Amaka Obi, Ada Nwosu, Bola Adeyemi
- Phone numbers like 0803 000 0000
- Ticket numbers 01 to 24
- Statuses: Booked, Waiting, In progress, Attended, Absent, Cancelled

CONSTRAINTS
Mobile-first. Every patient screen must work at 390px wide with no horizontal
scrolling. Assume a low-end screen and poor lighting: favour large type and high
contrast over subtle greys. Staff tables can be dense, patient screens must not be.
```

---

## 2. Style blocks — pick one

**A — Paper and board** *(the current design, for continuity)*

```
Manila folder ground #E9DBBC, stamp ink #161A16, hairline rules, 2px corners, no
drop shadows. One dark panel #0E1F19 carrying amber #FFB627 split-flap digits like
a clinic now-serving board. Condensed grotesque display type, monospace for all
numbers.
```

**B — Clinical calm**

```
Soft white and pale blue-grey, one deep teal accent, generous whitespace, 16px
rounded corners, gentle shadows, large friendly sans-serif. Reassuring and
unhurried — public-health service design.
```

**C — High-contrast utility**

```
Near-black on white. Enormous numerals, chunky buttons, zero decoration, thick
rules. Built to be read in direct sunlight on a scratched screen. Brutally legible
rather than pretty.
```

**D — Your own.** Name a ground colour, one accent, a corner radius, whether shadows are
allowed, and a display/body type pairing. Those five decisions carry most of the look.

---

## 3. Screen prompts — feed one at a time

### Patient: My queue *(the important one — do this first)*

```
Screen: "My queue" for a patient on a phone.

The hero is the live queue position and nothing competes with it:
- Their ticket number: 07
- Now serving: 04
- "3 patients ahead of you"
- A one-line instruction: "Almost your turn. Stay close by."
- Department name (General Outpatient) and the date
- A quiet "checked 14:22:09" timestamp so it reads as live

Show the position visually as well as numerically, so it can be understood without
reading — the number of people ahead should be graspable in one glance.

Below it, a compact list of the patient's appointments: ticket number, department,
date, status badge, and a Cancel action on upcoming ones.
```

### Patient: Book an appointment

```
Screen: booking form on a phone.
Department dropdown (General Outpatient, Antenatal Care, Immunisation, Pharmacy,
Laboratory, Dental, each showing opening hours like 08:00–16:00), a date picker,
and one primary button "Book and get my ticket".
Then the confirmation state: a large ticket number 07, the department and full date,
"Keep this number. You will be called by it", and a button to go watch the queue.
Make the confirmation feel like being handed a physical ticket.
```

### Nurse: Queue board

```
Screen: the queue board a nurse keeps open all day, on desktop.
Top: department selector, date picker, and a "Call next patient" button.
A summary strip: now serving 04, still waiting 09, seen 12, booked 21.
Main area: the day's register in ticket order — ticket number, patient name, phone,
status badge, and two actions per row, "Seen" and "Absent". Finished rows show no
actions. Dense and scannable; this is a working tool, not a showcase.
```

### Admin: Overview

```
Screen: administrator dashboard.
Headline figures for today: waiting now, seen today, attendance rate.
Counts for registered patients, staff accounts and departments.
Three cards linking to Departments, Staff accounts and Reports.
```

### Admin: Reports

```
Screen: reports, with a from/to date range.
Four sections: attendance today (booked / seen / absent / turned-up rate),
patients by department as a horizontal bar chart, busiest hours by hour of day,
and no-shows by department. Simple bars, no 3D, no gradients. Include an empty
state that reads as an invitation rather than an error.
```

### Landing page

```
Screen: public landing page.
Headline "Hold your place from anywhere." Explain that patients book at a public
health centre, get a queue number immediately, and watch how many people are ahead
of them from their phone instead of waiting in a crowded hall.
Show a realistic preview of the live queue position as the hero — the product
itself, not an abstract illustration.
Then three short benefit sections and a demo-accounts table.
```

---

## 4. Using the output

Stitch gives you a design plus its own code. That code will not drop into MediQueue —
this app is plain Java serving static HTML/CSS/JS with no build step or framework. Treat
the output as **visual direction**: palette, type, spacing, layout, component shapes.

What to keep whatever the redesign looks like:

- The live position must stay glanceable — that is the entire point of the product.
- Patient screens must not scroll sideways at 390px.
- Status colours must not be the only way to tell states apart (colour-blind users).
- Visible keyboard focus, and motion that respects `prefers-reduced-motion`.
