/*
 * Shared browser helpers: talking to the API, guarding pages, rendering fragments.
 *
 * Every page loads this first, so an individual page script only describes what it shows.
 */

/**
 * Calls the MediQueue API.
 *
 * Sends form encoding rather than JSON, which is what lets the server parse request
 * bodies with URLDecoder instead of a JSON parsing library.
 */
async function api(path, { method = 'GET', body } = {}) {
    const options = {
        method,
        // Without this the browser omits the session cookie and every call is a 401.
        credentials: 'same-origin',
        headers: {}
    };

    if (body) {
        options.headers['Content-Type'] = 'application/x-www-form-urlencoded';
        options.body = new URLSearchParams(body).toString();
    }

    const response = await fetch(path, options);
    const text = await response.text();

    let data = null;
    if (text) {
        try {
            data = JSON.parse(text);
        } catch {
            throw new Error('The server sent something we could not read. Try again.');
        }
    }

    if (!response.ok) {
        // Surface the server's message, which was written to be read by a patient.
        throw new Error((data && data.error) || `Request failed (${response.status})`);
    }
    return data;
}

/**
 * Loads the signed-in user, sending anyone without a session to the sign-in page.
 *
 * A convenience, not a security control: the server re-checks every request. Hiding a
 * button never stops anyone who can type a URL.
 */
async function requireUser(allowedRoles) {
    let user;
    try {
        user = await api('/api/auth/me');
    } catch {
        window.location.href = '/login.html';
        return null;
    }
    if (allowedRoles && !allowedRoles.includes(user.role)) {
        window.location.href = user.landingPage;
        return null;
    }
    return user;
}

/**
 * Marks a demonstration instance on every page.
 *
 * <p>The banner is the reason MediQueue no longer refuses to run demo accounts over
 * HTTPS. A public demonstration is a reasonable thing to want; the danger was never that
 * the accounts existed, it was that somebody could mistake the demo for a working clinic
 * system and type in a real patient's details. Saying so on every page fixes that, and
 * unlike a startup check it stays visible.
 *
 * <p>Runs on every page including sign-in, which is why /api/meta needs no session.
 */
async function markDemoInstance() {
    try {
        const meta = await api('/api/meta');
        if (!meta.demoMode || document.querySelector('.demo-banner')) return;

        document.body.insertAdjacentHTML('afterbegin',
            '<div class="demo-banner" role="status">' +
            'Demonstration system — do not enter real patient information' +
            '</div>');
    } catch {
        // An instance that cannot answer is not one we can label. Never block the page.
    }
}

document.addEventListener('DOMContentLoaded', markDemoInstance);

/* Icons for the mobile action bar. Inline so the bar needs no extra request and works
   before any font or icon set has loaded. */
const ICON = {
    queue: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M4 7V5a1 1 0 0 1 1-1h14a1 1 0 0 1 1 1v2a2 2 0 0 0 0 4v6a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1v-6a2 2 0 0 0 0-4z"/><path d="M12 8v8"/></svg>',
    book:  '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="5" width="18" height="16" rx="2"/><path d="M8 3v4M16 3v4M3 11h18M12 15v4M10 17h4"/></svg>',
    out:   '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><path d="M16 17l5-5-5-5M21 12H9"/></svg>'
};

/**
 * Fixed bar at the foot of patient screens.
 *
 * Patients use this standing up, one-handed, in a queue. Primary navigation belongs
 * within thumb reach rather than in a header they have to stretch for.
 */
function renderTabbar(current) {
    const tab = (href, key, label) => {
        const here = current === key ? ' aria-current="page"' : '';
        return `<a href="${href}"${here}>${ICON[key]}<span>${label}</span></a>`;
    };
    document.body.insertAdjacentHTML('beforeend', `
        <nav class="tabbar" aria-label="Main">
            ${tab('/patient/dashboard.html', 'queue', 'My queue')}
            ${tab('/patient/book.html', 'book', 'Book')}
            <a href="#" id="tabSignOut">${ICON.out}<span>Sign out</span></a>
        </nav>
    `);
    document.getElementById('tabSignOut').addEventListener('click', async event => {
        event.preventDefault();
        await api('/api/auth/logout', { method: 'POST' });
        window.location.href = '/login.html';
    });
}

/**
 * Side rail for staff and administrators.
 *
 * These screens stay open all day and keep gaining sections, so navigation sits down the
 * side where it can grow, rather than in a header row that runs out of width. Collapses
 * to a horizontal strip on narrow screens.
 */
function renderRail(user, links, current) {
    document.documentElement.classList.add('has-rail');

    const items = links.map(link => {
        const here = current === link.key ? ' aria-current="page"' : '';
        return `<a href="${link.href}"${here}>${escapeHtml(link.label)}</a>`;
    }).join('');

    const shell = document.createElement('div');
    shell.className = 'shell';
    shell.innerHTML = `
        <aside class="rail">
            <a class="wordmark" href="${user.landingPage}">
                <img src="/img/mark.svg" alt="" width="24" height="24">MediQueue</a>
            <p class="org">${escapeHtml(user.fullName)}</p>
            <nav class="rail-nav" aria-label="Main">${items}</nav>
            <div class="rail-foot">
                <button class="secondary tight" id="railSignOut">Sign out</button>
            </div>
        </aside>
    `;

    // Move the existing <main> inside the shell so the rail and content sit side by side.
    const main = document.querySelector('main');
    document.body.insertBefore(shell, main);
    shell.appendChild(main);

    document.getElementById('railSignOut').addEventListener('click', async () => {
        await api('/api/auth/logout', { method: 'POST' });
        window.location.href = '/login.html';
    });
}

/** Renders the shared header. Public and patient screens only. */
function renderTopbar(user, links = []) {
    const nav = links
        .map(link => `<a href="${link.href}">${escapeHtml(link.label)}</a>`)
        .join('');

    const header = `
        <header class="bar">
            <a class="wordmark" href="${user ? user.landingPage : '/'}">
                <img src="/img/mark.svg" alt="" width="22" height="22">MediQueue</a>
            <nav>
                ${nav}
                ${user ? `<span class="who">${escapeHtml(user.fullName)}</span>` : ''}
                ${user ? '<a href="#" id="signOut">Sign out</a>' : ''}
            </nav>
        </header>
    `;

    // The banner and this header race: both are inserted from async work. Whichever
    // lands second must not end up on top, so each defers to the other explicitly.
    const banner = document.querySelector('.demo-banner');
    if (banner) {
        banner.insertAdjacentHTML('afterend', header);
    } else {
        document.body.insertAdjacentHTML('afterbegin', header);
    }

    const signOut = document.getElementById('signOut');
    if (signOut) {
        signOut.addEventListener('click', async event => {
            event.preventDefault();
            await api('/api/auth/logout', { method: 'POST' });
            window.location.href = '/login.html';
        });
    }
}

/**
 * Escapes text before it goes anywhere near innerHTML.
 *
 * Patient names come from a registration form, so they are untrusted. Without this, a
 * patient registering as <img src=x onerror=...> would run script in the browser of
 * every staff member who opened the queue board -- stored cross-site scripting. The
 * server escapes for JSON; this escapes for HTML, and both are needed.
 */
function escapeHtml(value) {
    if (value === null || value === undefined) return '';
    return String(value)
        .replaceAll('&', '&amp;')
        .replaceAll('<', '&lt;')
        .replaceAll('>', '&gt;')
        .replaceAll('"', '&quot;')
        .replaceAll("'", '&#39;');
}

/**
 * Sets a split-flap digit, flipping only when the value actually changes.
 *
 * The flip is the one piece of motion in MediQueue, and it earns its place: it fires at
 * the exact moment a patient moves up the queue, which is the moment they need to
 * notice. Flipping on every poll would make it wallpaper.
 */
function setFlip(element, value) {
    if (!element) return;
    const face = element.querySelector('.flip-face');
    const next = String(value);
    if (face.textContent === next) return;

    element.classList.remove('is-turning');
    void element.offsetWidth;          // restart the animation
    element.classList.add('is-turning');
    // Swap the text at the midpoint, while the flap is edge-on and unreadable.
    setTimeout(() => { face.textContent = next; }, 130);
}

/** Two-digit ticket formatting, as printed on the stub. */
function pad(n) {
    return String(n).padStart(2, '0');
}

/** Shows a message. */
function showNotice(elementId, message, kind = 'error') {
    const box = document.getElementById(elementId);
    if (!box) return;
    box.className = `notice ${kind}`;
    box.textContent = message; // textContent, never innerHTML: cannot inject markup.
}

function clearNotice(elementId) {
    const box = document.getElementById(elementId);
    if (box) { box.textContent = ''; box.className = 'notice'; }
}

/** Today as YYYY-MM-DD in the browser's timezone, for date inputs. */
function today() {
    const now = new Date();
    return new Date(now - now.getTimezoneOffset() * 60000).toISOString().slice(0, 10);
}

/** Formats an ISO date as e.g. "Wed 9 Sept". */
function formatDate(iso) {
    if (!iso) return '';
    return new Date(iso + 'T00:00:00')
        .toLocaleDateString('en-GB', { weekday: 'short', day: 'numeric', month: 'short' });
}

function formatFullDate(iso) {
    if (!iso) return '';
    return new Date(iso + 'T00:00:00')
        .toLocaleDateString('en-GB', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' });
}

/** Wires a form so submitting it calls the API and reports failures in one place. */
function onSubmit(formId, noticeId, handler) {
    const form = document.getElementById(formId);
    form.addEventListener('submit', async event => {
        event.preventDefault();
        clearNotice(noticeId);

        const button = form.querySelector('button[type=submit]');
        const original = button ? button.textContent : null;
        if (button) {
            // Disabling the button is what stops a double-click booking twice.
            button.disabled = true;
            button.textContent = 'Working…';
        }

        try {
            await handler(Object.fromEntries(new FormData(form)));
        } catch (error) {
            showNotice(noticeId, error.message);
        } finally {
            if (button) { button.disabled = false; button.textContent = original; }
        }
    });
}
