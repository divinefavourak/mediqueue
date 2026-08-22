/*
 * The patient's live queue status.
 *
 * One job: answer "how much longer?" at arm's length, on a cheap phone, in a hall.
 *
 * Position is the largest element and the ticket number is reference material. That is
 * deliberate: the ticket never changes, while the position is the number being watched.
 *
 * Positions poll every 5 seconds. There is no refresh button, because offering one would
 * tell patients the page does not update itself, which is the entire promise.
 */

const POLL_MS = 5000;
const MAX_PIPS = 20;   /* beyond this the marks stop being countable */

let tracked = null;
let timer = null;

document.addEventListener('DOMContentLoaded', async () => {
    const user = await requireUser(['PATIENT']);
    if (!user) return;

    renderTopbar(user, []);
    renderTabbar('queue');

    document.getElementById('greeting').textContent = `Hello, ${user.fullName.split(' ')[0]}`;

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
            document.getElementById('whereWhen').textContent = 'Nothing booked.';
            return;
        }

        tbody.innerHTML = appointments.map(rowFor).join('');
        wireCancelButtons();

        const live = appointments
            .filter(a => ['BOOKED', 'WAITING', 'IN_PROGRESS'].includes(a.status))
            .sort((a, b) => a.date.localeCompare(b.date))[0];
        const recent = appointments.slice().sort((a, b) => b.date.localeCompare(a.date))[0];

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
        ? `<button class="critical tight" data-cancel="${escapeHtml(a.id)}">Cancel</button>`
        : '';
    return `
        <tr>
            <td class="ticket-no">${escapeHtml(pad(a.queueNumber))}</td>
            <td>${escapeHtml(a.departmentName)}</td>
            <td>${escapeHtml(formatDate(a.date))}</td>
            <td><span class="badge ${escapeHtml(a.status)}">${escapeHtml(statusWord(a.status))}</span></td>
            <td>${cancel}</td>
        </tr>`;
}

function statusWord(status) {
    return { IN_PROGRESS: 'In progress', SKIPPED: 'Absent' }[status]
        || status.charAt(0) + status.slice(1).toLowerCase();
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
        p.finished ? renderVerdict(p) : renderStatus(p);
    } catch (error) {
        // A network blip should not replace a live position with an error. Retry next tick.
        console.warn('Could not refresh position:', error.message);
    }
}

function renderStatus(p) {
    show('livePosition'); hide('verdictBoard');

    const card = document.getElementById('livePosition');
    card.classList.toggle('is-next', p.isNext);

    document.getElementById('whereWhen').textContent =
        `${p.departmentName} · ${whenLabel()}`;

    document.getElementById('positionNumber').innerHTML =
        `${p.position}<span class="ord">${ordinal(p.position)}</span>`;
    document.getElementById('positionSummary').textContent = p.summary;

    renderPips(p);

    // Shown only when the day has produced enough data to be honest. There is no
    // placeholder or dash: an absent estimate leaves no trace on screen.
    const estimate = document.getElementById('estimate');
    if (p.estimatedMinutes !== null && p.estimatedMinutes !== undefined && !p.isNext) {
        document.getElementById('estimateText').textContent = p.estimateText;
        estimate.hidden = false;
    } else {
        estimate.hidden = true;
    }

    document.getElementById('ticketNumber').textContent = pad(p.queueNumber);
    document.getElementById('nowServing').textContent =
        p.nowServing > 0 ? pad(p.nowServing) : 'Not started';
    document.getElementById('lastChecked').textContent =
        'Updates on its own · checked ' +
        new Date().toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
}

/** 1st, 2nd, 3rd, 4th. */
function ordinal(n) {
    if (n % 100 >= 11 && n % 100 <= 13) return 'th';
    return { 1: 'st', 2: 'nd', 3: 'rd' }[n % 10] || 'th';
}

/**
 * The queue as a row of marks: those ahead, then you.
 *
 * A number tells you where you are; the marks show you, which is faster at arm's length.
 * Above MAX_PIPS they stop being countable, so the strip is dropped rather than shown as
 * a smear that implies a count nobody can take.
 */
function renderPips(p) {
    const strip = document.getElementById('pips');
    const ahead = p.aheadCount;

    if (ahead > MAX_PIPS) {
        strip.innerHTML = '';
        strip.setAttribute('aria-label', `${ahead} patients ahead of you`);
        return;
    }
    strip.innerHTML = '<span class="pip ahead"></span>'.repeat(ahead) + '<span class="pip mine"></span>';
    strip.setAttribute('aria-label',
        ahead === 0 ? 'You are next' : `${ahead} ahead of you`);
}

function renderVerdict(p) {
    hide('livePosition'); show('verdictBoard');

    document.getElementById('whereWhen').textContent = `${p.departmentName} · ${whenLabel()}`;
    document.getElementById('verdictWhen').textContent = 'This appointment';
    document.getElementById('verdictStamp').textContent =
        { ATTENDED: 'Seen', SKIPPED: 'Marked absent', CANCELLED: 'Cancelled' }[p.status] || p.status;
    document.getElementById('verdictSay').textContent = p.summary;

    stopPolling();
    loadAppointmentsQuietly();
}

async function loadAppointmentsQuietly() {
    try {
        const appointments = await api('/api/appointments/mine');
        if (appointments.length) {
            document.getElementById('appointmentRows').innerHTML = appointments.map(rowFor).join('');
            wireCancelButtons();
        }
    } catch { /* the card already shows the outcome */ }
}

/** "Today" is printed only when it really is today. */
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
