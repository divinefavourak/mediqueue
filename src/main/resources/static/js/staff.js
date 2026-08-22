/*
 * The nurse's queue board.
 *
 * Kept open all day, so it is a register first and a display second. The patient
 * currently in the room is lifted out of the table with their two actions attached,
 * because that is the one the nurse acts on most often.
 *
 * Per-row "Seen" and "Absent" stay as well. A patient who never got called still has to
 * be recordable, and a board offering only "Cancel" on its rows cannot do that.
 */

const REFRESH_MS = 5000;
let timer = null;
let board = null;

const RAIL = [
    { key: 'queue',       href: '/staff/dashboard.html',     label: 'Queue board' },
    { key: 'departments', href: '/admin/departments.html',   label: 'Departments' },
    { key: 'staff',       href: '/admin/staff.html',         label: 'Staff' },
    { key: 'reports',     href: '/admin/reports.html',       label: 'Reports' }
];

document.addEventListener('DOMContentLoaded', async () => {
    const user = await requireUser(['STAFF', 'ADMIN']);
    if (!user) return;

    // Nurses do not administer departments or accounts, so those sections are absent
    // rather than present-and-forbidden.
    const links = user.canAdminister
        ? [{ key: 'overview', href: '/admin/dashboard.html', label: 'Overview' }, ...RAIL]
        : [RAIL[0], RAIL[3]];
    renderRail(user, links, 'queue');

    document.getElementById('date').value = today();

    await loadDepartments(user);
    await refresh();

    document.getElementById('departmentId').addEventListener('change', refresh);
    document.getElementById('date').addEventListener('change', refresh);
    document.getElementById('callNext').addEventListener('click', callNext);
    document.getElementById('servingSeen').addEventListener('click', () => actOnServing('attend'));
    document.getElementById('servingAbsent').addEventListener('click', () => actOnServing('skip'));

    timer = setInterval(refresh, REFRESH_MS);
});

async function loadDepartments(user) {
    const select = document.getElementById('departmentId');
    try {
        const departments = await api('/api/departments');
        if (departments.length === 0) {
            select.innerHTML = '<option value="">No departments</option>';
            return;
        }
        select.innerHTML = departments
            .map(d => `<option value="${escapeHtml(d.id)}">${escapeHtml(d.name)}</option>`)
            .join('');
        // A nurse should land on their own department, not an arbitrary one.
        if (user.departmentId) select.value = user.departmentId;
    } catch (error) {
        showNotice('notice', error.message);
    }
}

async function refresh() {
    const departmentId = document.getElementById('departmentId').value;
    const date = document.getElementById('date').value;
    if (!departmentId || !date) return;

    try {
        board = await api(`/api/queue/${departmentId}?date=${encodeURIComponent(date)}`);
        render();
    } catch (error) {
        console.warn('Queue refresh failed:', error.message);
    }
}

function render() {
    const rows = board.appointments;
    const select = document.getElementById('departmentId');
    const attended = rows.filter(a => a.status === 'ATTENDED').length;
    const serving = rows.find(a => a.status === 'IN_PROGRESS');

    document.getElementById('deptName').textContent =
        select.options[select.selectedIndex] ? select.options[select.selectedIndex].text : 'Queue board';
    document.getElementById('chipWaiting').textContent = `${board.waiting} waiting`;
    document.getElementById('chipSeen').textContent = `${attended} seen today`;
    document.getElementById('registerCount').textContent =
        board.total === 0 ? '' : `${board.total} booked`;

    renderServing(serving);

    const tbody = document.getElementById('queueRows');
    if (rows.length === 0) {
        tbody.innerHTML = `<tr><td colspan="5" class="empty">
            Nobody is booked for this day. Choose another date, or another department.
        </td></tr>`;
        return;
    }
    tbody.innerHTML = rows.map(rowFor).join('');
    wireRowActions();
}

function renderServing(serving) {
    const card = document.getElementById('servingCard');
    const actions = document.getElementById('servingActions');

    if (!serving) {
        card.classList.add('idle');
        document.getElementById('servingNo').textContent = '—';
        document.getElementById('servingWho').textContent =
            board && board.waiting > 0 ? 'Nobody called yet' : 'Nobody waiting';
        actions.hidden = true;
        card.dataset.id = '';
        return;
    }
    card.classList.remove('idle');
    document.getElementById('servingNo').textContent = pad(serving.queueNumber);
    document.getElementById('servingWho').textContent = serving.patientName;
    actions.hidden = false;
    card.dataset.id = serving.id;
}

function rowFor(a) {
    // Only live appointments get buttons. Offering them on a finished row invites a
    // click that can only fail.
    const live = ['BOOKED', 'WAITING', 'IN_PROGRESS'].includes(a.status);
    const actions = live
        ? `<button class="tight" data-attend="${escapeHtml(a.id)}">Seen</button>
           <button class="secondary tight" data-skip="${escapeHtml(a.id)}">Absent</button>`
        : '';
    return `
        <tr>
            <td class="ticket-no">${escapeHtml(pad(a.queueNumber))}</td>
            <td>${escapeHtml(a.patientName)}</td>
            <td>${escapeHtml(a.patientPhone) || '<span class="muted">—</span>'}</td>
            <td><span class="badge ${escapeHtml(a.status)}">${escapeHtml(statusWord(a.status))}</span></td>
            <td><span class="cell-actions">${actions}</span></td>
        </tr>`;
}

function statusWord(status) {
    return { IN_PROGRESS: 'In progress', SKIPPED: 'Absent' }[status]
        || status.charAt(0) + status.slice(1).toLowerCase();
}

function wireRowActions() {
    document.querySelectorAll('[data-attend]').forEach(b =>
        b.addEventListener('click', () => act(b, b.dataset.attend, 'attend')));
    document.querySelectorAll('[data-skip]').forEach(b =>
        b.addEventListener('click', () => act(b, b.dataset.skip, 'skip')));
}

function actOnServing(action) {
    const id = document.getElementById('servingCard').dataset.id;
    if (!id) return;
    act(document.getElementById(action === 'attend' ? 'servingSeen' : 'servingAbsent'), id, action);
}

/**
 * Records an outcome, then refreshes.
 *
 * The refresh is where the queue logic becomes visible: marking one patient seen
 * recomputes everyone else's position on the server, with no stored positions to update.
 */
async function act(button, appointmentId, action) {
    button.disabled = true;
    clearNotice('notice');
    try {
        const result = await api(`/api/queue/${appointmentId}/${action}`, { method: 'POST' });
        showNotice('notice', result.message, 'success');
        await refresh();
    } catch (error) {
        showNotice('notice', error.message);
    } finally {
        button.disabled = false;
    }
}

async function callNext() {
    const departmentId = document.getElementById('departmentId').value;
    const date = document.getElementById('date').value;
    const button = document.getElementById('callNext');

    button.disabled = true;
    clearNotice('notice');
    try {
        const result = await api(
            `/api/queue/${departmentId}/call-next?date=${encodeURIComponent(date)}`,
            { method: 'POST' });
        showNotice('notice',
            `Called ticket ${pad(result.appointment.queueNumber)} — ${result.appointment.patientName}.`,
            'success');
        await refresh();
    } catch (error) {
        showNotice('notice', error.message);
    } finally {
        button.disabled = false;
    }
}
