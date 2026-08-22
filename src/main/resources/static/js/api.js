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

/** Renders the shared header. */
function renderTopbar(user, links = []) {
    const nav = links
        .map(link => `<a href="${link.href}">${escapeHtml(link.label)}</a>`)
        .join('');

    document.body.insertAdjacentHTML('afterbegin', `
        <header class="bar">
            <a class="wordmark" href="${user ? user.landingPage : '/'}">MediQueue</a>
            <nav>
                ${nav}
                ${user ? `<span class="who">${escapeHtml(user.fullName)}</span>` : ''}
                ${user ? '<a href="#" id="signOut">Sign out</a>' : ''}
            </nav>
        </header>
    `);

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
