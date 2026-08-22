/*
 * Reporting.
 *
 * Charts are plain HTML bars sized by percentage. No charting library, which keeps
 * MediQueue dependency-free and leaves the numbers in the markup as text, where a screen
 * reader can still reach them.
 */

document.addEventListener('DOMContentLoaded', async () => {
    const user = await requireUser(['ADMIN', 'STAFF']);
    if (!user) return;

    const links = [{ href: '/staff/dashboard.html', label: 'Queue board' }];
    if (user.canAdminister) {
        links.unshift(
            { href: '/admin/dashboard.html', label: 'Overview' },
            { href: '/admin/departments.html', label: 'Departments' },
            { href: '/admin/staff.html', label: 'Staff' });
    }
    renderTopbar(user, links);

    // The range spans 30 days each way. A history-only range would hide every upcoming
    // appointment and report zero on a newly installed system, which looks broken.
    const iso = ms => new Date(ms).toISOString().slice(0, 10);
    document.getElementById('from').value = iso(Date.now() - 30 * 86400000);
    document.getElementById('to').value = iso(Date.now() + 30 * 86400000);

    document.getElementById('apply').addEventListener('click', loadAll);
    await loadAll();
});

async function loadAll() {
    const from = document.getElementById('from').value;
    const to = document.getElementById('to').value;
    const range = `from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`;

    clearNotice('notice');
    try {
        // All four are independent, so they run concurrently.
        const [attendance, byDepartment, peaks, noShows] = await Promise.all([
            // Attendance is always about one day, and the useful one is today -- not the
            // end of the range, which sits a month in the future.
            api(`/api/reports/daily-attendance?date=${encodeURIComponent(today())}`),
            api(`/api/reports/by-department?${range}`),
            api(`/api/reports/peak-periods?${range}`),
            api(`/api/reports/no-shows?${range}`)
        ]);

        renderAttendance(attendance);
        renderBars('byDepartment', byDepartment, 'No appointments in this range.');
        renderBars('peakPeriods', peaks, 'No patients marked seen yet.');
        renderBars('noShows', noShows, 'Nobody missed an appointment. Good news.');
    } catch (error) {
        showNotice('notice', error.message);
    }
}

function renderAttendance(report) {
    document.getElementById('attendanceDate').textContent = formatFullDate(report.date);
    document.getElementById('dBooked').textContent = report.booked;
    document.getElementById('dAttended').textContent = report.attended;
    document.getElementById('dSkipped').textContent = report.skipped;
    document.getElementById('dRate').textContent = `${report.attendanceRate}%`;
}

/** Draws a horizontal bar chart from a {rows:[{label,value}]} report. */
function renderBars(elementId, report, emptyMessage) {
    const container = document.getElementById(elementId);
    const rows = report.rows.filter(row => row.value > 0);

    if (rows.length === 0) {
        container.innerHTML = `<p class="empty">${escapeHtml(emptyMessage)}</p>`;
        return;
    }

    // Scale against the largest bar so the chart uses the full width whatever the
    // absolute numbers are. Guarded against zero.
    const largest = Math.max(...rows.map(row => row.value), 1);

    container.innerHTML = rows.map(row => `
        <div class="bar-row">
            <div class="k" title="${escapeHtml(row.label)}">${escapeHtml(row.label)}</div>
            <div class="track">
                <div class="fill" style="width:${(row.value / largest * 100).toFixed(1)}%"></div>
            </div>
            <div class="v">${escapeHtml(row.value)}</div>
        </div>`).join('');
}
