/*
 * The nurse's queue board.
 *
 * This screen stays open all day, so it is a register first and a display second: dense,
 * legible, in ticket order. The board strip at the top mirrors what patients see in the
 * hall, so staff and patients are never reading different numbers.
 */

const REFRESH_MS = 5000;
let timer = null;

document.addEventListener('DOMContentLoaded', async () => {
    const user = await requireUser(['STAFF', 'ADMIN']);
    if (!user) return;

    const links = [{ href: '/staff/dashboard.html', label: 'Queue board' }];
    if (user.canAdminister) links.push({ href: '/admin/dashboard.html', label: 'Admin' });
    renderTopbar(user, links);

    document.getElementById('date').value = today();

    await loadDepartments(user);
    await refresh();

    document.getElementById('departmentId').addEventListener('change', refresh);
    document.getElementById('date').addEventListener('change', refresh);
    document.getElementById('callNext').addEventListener('click', callNext);

    timer = setInterval(refresh, REFRESH_MS);
});

/** Fills the selector, defaulting to the nurse's own department. */
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
        render(await api(`/api/queue/${departmentId}?date=${encodeURIComponent(date)}`));
    } catch (error) {
        console.warn('Queue refresh failed:', error.message);
    }
}

function render(board) {
    const rows = board.appointments;
    const tbody = document.getElementById('queueRows');
    const serving = rows.find(a => a.status === 'IN_PROGRESS');
    const attended = rows.filter(a => a.status === 'ATTENDED').length;

    const select = document.getElementById('departmentId');
    document.getElementById('boardWhere').textContent =
        select.options[select.selectedIndex] ? select.options[select.selectedIndex].text : '—';
    document.getElementById('boardWhen').textContent = formatDate(board.date);

    const servingFlip = document.getElementById('nowServing');
    servingFlip.classList.toggle('dim', !serving);
    setFlip(servingFlip, serving ? pad(serving.queueNumber) : '––');
    setFlip(document.getElementById('stillWaiting'), pad(board.waiting));

    document.getElementById('boardSay').textContent =
        board.total === 0 ? 'Nobody booked for this day.'
        : serving ? `Seeing ${serving.patientName}.`
        : board.waiting > 0 ? 'Nobody called yet.'
        : 'Everyone has been seen.';

    document.getElementById('boardTime').textContent =
        'Updated ' + new Date().toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit', second: '2-digit' });

    document.getElementById('registerCount').textContent =
        board.total === 0 ? '' : `${attended} seen · ${board.waiting} waiting · ${board.total} booked`;

    if (rows.length === 0) {
        tbody.innerHTML = `<tr><td colspan="5" class="empty">
            Nobody is booked for this day. Choose another date, or another department.
        </td></tr>`;
        return;
    }

    tbody.innerHTML = rows.map(rowFor).join('');
    wireActions();
}

function rowFor(a) {
    // Only live appointments get buttons. Offering them on a finished row invites a
    // click that can only fail.
    const live = ['BOOKED', 'WAITING', 'IN_PROGRESS'].includes(a.status);
    const actions = live
        ? `<button class="tight" data-attend="${escapeHtml(a.id)}">Seen</button>
           <button class="tight quiet" data-skip="${escapeHtml(a.id)}">Absent</button>`
        : '';

    return `
        <tr>
            <td class="ticket-no">${escapeHtml(pad(a.queueNumber))}</td>
            <td>${escapeHtml(a.patientName)}</td>
            <td class="mono" style="font-size:.86rem">${escapeHtml(a.patientPhone) || '—'}</td>
            <td><span class="stamp ${escapeHtml(a.status)}">${escapeHtml(a.status.replace('_', ' '))}</span></td>
            <td><span style="display:flex;gap:.4rem;flex-wrap:wrap">${actions}</span></td>
        </tr>`;
}

function wireActions() {
    document.querySelectorAll('[data-attend]').forEach(b =>
        b.addEventListener('click', () => act(b, b.dataset.attend, 'attend')));
    document.querySelectorAll('[data-skip]').forEach(b =>
        b.addEventListener('click', () => act(b, b.dataset.skip, 'skip')));
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
