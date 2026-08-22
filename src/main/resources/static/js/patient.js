/*
 * The patient's board.
 *
 * One job: answer "how much longer?" at arm's length, on a cheap phone, in a hall.
 *
 * Positions poll every 5 seconds. That is the honest choice for a first version -- it
 * survives every proxy and mobile network, needs no persistent connection, and 5 seconds
 * reads as live when a consultation lasts minutes. Server-Sent Events is the upgrade;
 * the JDK's HttpServer supports it by holding the response open.
 */

const POLL_MS = 5000;
/* Beyond this the strip stops being countable, so it becomes a proportional bar. */
const MAX_STUBS = 40;

/* The whole appointment is kept, not just its id: the position endpoint returns queue
   figures but not the date, and the board must never claim "Today" for a visit booked
   three weeks out. */
let tracked = null;
let timer = null;

document.addEventListener('DOMContentLoaded', async () => {
    const user = await requireUser(['PATIENT']);
    if (!user) return;

    renderTopbar(user, [
        { href: '/patient/dashboard.html', label: 'Your queue' },
        { href: '/patient/book.html', label: 'Book' }
    ]);

    await loadAppointments();
    startPolling();
});

async function loadAppointments() {
    const tbody = document.getElementById('appointmentRows');

    try {
        const appointments = await api('/api/appointments/mine');

        if (appointments.length === 0) {
            // An empty screen is an invitation to act, not an apology.
            tbody.innerHTML = `<tr><td colspan="5" class="empty">
                No appointments yet. Book one and you will get a queue number straight away.
            </td></tr>`;
            hideBoards();
            tracked = null;
            return;
        }

        tbody.innerHTML = appointments.map(rowFor).join('');
        wireCancelButtons();

        // Track the soonest appointment still in a queue.
        const live = appointments
            .filter(a => ['BOOKED', 'WAITING', 'IN_PROGRESS'].includes(a.status))
            .sort((a, b) => a.date.localeCompare(b.date))[0];

        // Otherwise show the most recent finished one, so the board is never blank
        // straight after a visit ends.
        const recent = appointments
            .slice()
            .sort((a, b) => b.date.localeCompare(a.date))[0];

        tracked = live || recent || null;
        if (tracked) await refreshPosition();

    } catch (error) {
        showNotice('notice', error.message);
        tbody.innerHTML = '<tr><td colspan="5" class="empty">Could not load your appointments.</td></tr>';
    }
}

function rowFor(a) {
    // Every interpolated value is escaped: names originate in a registration form.
    const cancel = a.cancellable
        ? `<button class="warn tight" data-cancel="${escapeHtml(a.id)}">Cancel</button>`
        : '';
    return `
        <tr>
            <td class="ticket-no">${escapeHtml(pad(a.queueNumber))}</td>
            <td>${escapeHtml(a.departmentName)}</td>
            <td>${escapeHtml(formatDate(a.date))}</td>
            <td><span class="stamp ${escapeHtml(a.status)}">${escapeHtml(a.status.replace('_', ' '))}</span></td>
            <td>${cancel}</td>
        </tr>`;
}

function wireCancelButtons() {
    document.querySelectorAll('[data-cancel]').forEach(button => {
        button.addEventListener('click', async () => {
            if (!confirm('Cancel this appointment? You will give up your queue number.')) return;

            button.disabled = true;
            try {
                await api(`/api/appointments/${button.dataset.cancel}`, { method: 'DELETE' });
                showNotice('notice', 'Appointment cancelled.', 'success');
                await loadAppointments();
            } catch (error) {
                showNotice('notice', error.message);
                button.disabled = false;
            }
        });
    });
}

async function refreshPosition() {
    if (!tracked) return;

    try {
        const p = await api(`/api/queue/position/${tracked.id}`);
        p.finished ? renderVerdict(p) : renderBoard(p);
    } catch (error) {
        // A network blip should not replace a live queue position with an error. Log it
        // and let the next tick retry.
        console.warn('Could not refresh position:', error.message);
    }
}

function renderBoard(p) {
    show('livePosition'); hide('verdictBoard');

    document.getElementById('boardWhere').textContent = p.departmentName;
    document.getElementById('boardWhen').textContent = whenLabel();

    setFlip(document.getElementById('ticketNumber'), pad(p.queueNumber));

    // An empty well reads as "not started yet" on a real board; a word would not fit.
    const serving = document.getElementById('nowServing');
    serving.classList.toggle('dim', p.nowServing === 0);
    setFlip(serving, p.nowServing > 0 ? pad(p.nowServing) : '––');

    renderStubs(p);

    document.getElementById('aheadLabel').textContent =
        p.aheadCount === 0 ? 'You are at the front' :
        p.aheadCount === 1 ? '1 patient ahead of you' :
        `${p.aheadCount} patients ahead of you`;

    document.getElementById('positionSummary').textContent = p.summary;
    document.getElementById('positionNumber').textContent = p.position;
    document.getElementById('lastChecked').textContent =
        'Checked ' + new Date().toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
}

/**
 * The stub strip: one ticket stub per waiting patient, yours lit.
 *
 * This is the part a patient reads first. A number tells you where you are; the strip
 * shows you, which is faster and works at arm's length. Above MAX_STUBS the stubs stop
 * being countable, so it degrades to three proportional blocks rather than lying with a
 * strip nobody can read.
 */
function renderStubs(p) {
    const strip = document.getElementById('stubs');
    const waiting = Math.max(p.totalWaiting, 1);
    const ahead = Math.min(p.aheadCount, waiting - 1);
    const behind = Math.max(waiting - ahead - 1, 0);

    if (waiting > MAX_STUBS) {
        strip.innerHTML =
            `<span class="stub ahead" style="flex:${ahead}"></span>` +
            `<span class="stub mine" style="flex:0 0 6px"></span>` +
            `<span class="stub behind" style="flex:${behind}"></span>`;
    } else {
        strip.innerHTML =
            '<span class="stub ahead"></span>'.repeat(ahead) +
            '<span class="stub mine"></span>' +
            '<span class="stub behind"></span>'.repeat(behind);
    }

    strip.setAttribute('aria-label',
        `You are number ${p.position} of ${p.totalWaiting} waiting.`);
}

/** Once the visit is over the board stamps it, the way the desk stamps a folder. */
function renderVerdict(p) {
    hide('livePosition'); show('verdictBoard');

    document.getElementById('verdictWhere').textContent = p.departmentName;
    document.getElementById('verdictWhen').textContent = whenLabel();

    const stamp = document.getElementById('verdictStamp');
    const bad = p.status === 'SKIPPED' || p.status === 'CANCELLED';
    stamp.textContent = { ATTENDED: 'Seen', SKIPPED: 'Missed', CANCELLED: 'Cancelled' }[p.status] || p.status;
    stamp.classList.toggle('bad', bad);

    document.getElementById('verdictSay').textContent = p.summary;
    document.getElementById('positionNumber').textContent = p.position;

    stopPolling();
    loadAppointmentsQuietly();
}

/** Refreshes the table after a visit ends, without re-entering the polling loop. */
async function loadAppointmentsQuietly() {
    try {
        const appointments = await api('/api/appointments/mine');
        if (appointments.length) {
            document.getElementById('appointmentRows').innerHTML = appointments.map(rowFor).join('');
            wireCancelButtons();
        }
    } catch { /* the board already shows the outcome */ }
}

/**
 * Names the day the board is showing.
 *
 * "Today" is only printed when it is actually today. A queue position for a visit three
 * weeks out is still meaningful, but labelling it "Today" would be plainly wrong.
 */
function whenLabel() {
    if (!tracked) return '';
    return tracked.date === today() ? 'Today' : formatFullDate(tracked.date);
}

function show(id) { document.getElementById(id).hidden = false; }
function hide(id) { document.getElementById(id).hidden = true; }
function hideBoards() { hide('livePosition'); hide('verdictBoard'); }

function startPolling() {
    stopPolling();
    timer = setInterval(refreshPosition, POLL_MS);

    // Polling a hidden tab spends a patient's data and battery for nothing.
    document.addEventListener('visibilitychange', () => {
        if (document.hidden) {
            stopPolling();
        } else if (tracked) {
            refreshPosition();
            timer = setInterval(refreshPosition, POLL_MS);
        }
    });
}

function stopPolling() {
    if (timer) { clearInterval(timer); timer = null; }
}
