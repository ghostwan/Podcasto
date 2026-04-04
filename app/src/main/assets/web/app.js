// Podcasto Web UI — Full Player & Library Management

// ========================
// Authentication
// ========================
async function checkAuth() {
    try {
        const res = await fetch('/api/auth-check');
        if (res.status === 401) {
            showLoginOverlay();
            return false;
        }
        const data = await res.json();
        if (data.authenticated) {
            hideLoginOverlay();
            return true;
        }
        showLoginOverlay();
        return false;
    } catch (e) {
        // If server is unreachable, show app anyway (local use)
        hideLoginOverlay();
        return true;
    }
}

function showLoginOverlay() {
    document.getElementById('login-overlay').style.display = '';
    document.getElementById('login-password').value = '';
    document.getElementById('login-error').style.display = 'none';
    // Focus password input
    setTimeout(() => document.getElementById('login-password').focus(), 100);
}

function toggleLoginPasswordVisibility() {
    const input = document.getElementById('login-password');
    const icon = document.getElementById('login-vis-icon');
    if (input.type === 'password') {
        input.type = 'text';
        icon.textContent = 'visibility_off';
    } else {
        input.type = 'password';
        icon.textContent = 'visibility';
    }
}

function hideLoginOverlay() {
    document.getElementById('login-overlay').style.display = 'none';
}

async function submitLogin() {
    const input = document.getElementById('login-password');
    const password = input.value;
    const errorEl = document.getElementById('login-error');

    if (!password) {
        errorEl.textContent = 'Veuillez entrer le mot de passe';
        errorEl.style.display = '';
        return;
    }

    try {
        const res = await fetch('/api/login', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ password }),
        });
        const data = await res.json();

        if (data.success) {
            hideLoginOverlay();
            loadLibrary();
        } else {
            if (res.status === 429) {
                errorEl.textContent = 'Trop de tentatives. R\u00e9essayez dans 15 minutes.';
            } else if (data.remainingAttempts > 0) {
                errorEl.textContent = `Mot de passe incorrect. ${data.remainingAttempts} tentative(s) restante(s).`;
            } else {
                errorEl.textContent = 'Mot de passe incorrect. Compte verrouill\u00e9.';
            }
            errorEl.style.display = '';
            input.value = '';
            input.focus();
        }
    } catch (e) {
        errorEl.textContent = 'Erreur de connexion: ' + e.message;
        errorEl.style.display = '';
    }
}

// Wrapper for fetch that intercepts 401 responses
async function authFetch(url, options) {
    const res = await fetch(url, options);
    if (res.status === 401) {
        showLoginOverlay();
        throw new Error('Authentication required');
    }
    return res;
}

// Override global fetch to intercept 401 on API calls (except auth endpoints)
const _originalFetch = window.fetch.bind(window);
window.fetch = async function(url, options) {
    const res = await _originalFetch(url, options);
    if (res.status === 401 && typeof url === 'string' && url.startsWith('/api/') && !url.includes('/api/login') && !url.includes('/api/auth-check')) {
        showLoginOverlay();
    }
    return res;
};

// ========================
// Settings
// ========================
async function loadSettings() {
    try {
        const res = await fetch('/api/settings/gemini-key');
        const data = await res.json();
        const status = document.getElementById('settings-key-status');
        if (data.configured) {
            if (data.source === 'user') {
                status.innerHTML = '<span class="material-icons-round" style="color:var(--primary);vertical-align:middle;margin-right:4px">check_circle</span>Cl\u00e9 configur\u00e9e (personnalis\u00e9e)';
            } else {
                status.innerHTML = '<span class="material-icons-round" style="color:var(--primary);vertical-align:middle;margin-right:4px">check_circle</span>Cl\u00e9 configur\u00e9e (int\u00e9gr\u00e9e)';
            }
            status.className = 'settings-status settings-status-ok';
        } else {
            status.innerHTML = '<span class="material-icons-round" style="color:var(--error);vertical-align:middle;margin-right:4px">warning</span>Aucune cl\u00e9 configur\u00e9e. Les fonctionnalit\u00e9s IA sont d\u00e9sactiv\u00e9es.';
            status.className = 'settings-status settings-status-warn';
        }
    } catch (e) {
        console.error('Failed to load settings', e);
    }
}

async function saveGeminiKey() {
    const input = document.getElementById('settings-gemini-key');
    const key = input.value.trim();
    if (!key) {
        showToast('Veuillez entrer une cl\u00e9 API');
        return;
    }
    try {
        const res = await fetch('/api/settings/gemini-key', {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ key }),
        });
        if (res.ok) {
            input.value = '';
            showToast('Cl\u00e9 API enregistr\u00e9e');
            loadSettings();
        } else {
            showToast('Erreur lors de l\'enregistrement');
        }
    } catch (e) {
        showToast('Erreur: ' + e.message);
    }
}

async function clearGeminiKey() {
    try {
        const res = await fetch('/api/settings/gemini-key', {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ key: '' }),
        });
        if (res.ok) {
            document.getElementById('settings-gemini-key').value = '';
            showToast('Cl\u00e9 API effac\u00e9e');
            loadSettings();
        }
    } catch (e) {
        showToast('Erreur: ' + e.message);
    }
}

function toggleGeminiKeyVisibility() {
    const input = document.getElementById('settings-gemini-key');
    const icon = document.getElementById('settings-vis-icon');
    if (input.type === 'password') {
        input.type = 'text';
        icon.textContent = 'visibility_off';
    } else {
        input.type = 'password';
        icon.textContent = 'visibility';
    }
}

// ========================
// Backup / Restore
// ========================
async function exportBackup() {
    const status = document.getElementById('backup-status');
    status.innerHTML = '<span class="material-icons-round" style="color:var(--primary);vertical-align:middle;margin-right:4px;font-size:16px">hourglass_top</span>Export en cours...';
    status.className = 'settings-status';
    try {
        const res = await fetch('/api/backup');
        if (!res.ok) throw new Error('Erreur serveur: ' + res.status);
        const data = await res.json();
        const json = JSON.stringify(data, null, 2);
        const blob = new Blob([json], { type: 'application/json' });
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        const date = new Date().toISOString().slice(0, 10);
        a.href = url;
        a.download = 'podcasto_backup_' + date + '.json';
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        URL.revokeObjectURL(url);
        status.innerHTML = '<span class="material-icons-round" style="color:var(--success);vertical-align:middle;margin-right:4px;font-size:16px">check_circle</span>Sauvegarde export\u00e9e avec succ\u00e8s';
        status.className = 'settings-status settings-status-ok';
        showToast('Sauvegarde export\u00e9e');
    } catch (e) {
        status.innerHTML = '<span class="material-icons-round" style="color:var(--error);vertical-align:middle;margin-right:4px;font-size:16px">error</span>Erreur: ' + e.message;
        status.className = 'settings-status settings-status-warn';
        showToast('Erreur: ' + e.message);
    }
}

function importBackupDialog() {
    document.getElementById('dialog-title').textContent = 'Importer une sauvegarde';
    document.getElementById('dialog-text').innerHTML = '<p>Cette action remplacera toutes vos donn\u00e9es actuelles par celles du fichier de sauvegarde.</p><p style="color:var(--error);margin-top:8px"><strong>Cette op\u00e9ration est irr\u00e9versible.</strong></p>';
    document.getElementById('dialog-confirm').textContent = 'Choisir un fichier';
    document.getElementById('dialog-confirm').className = 'btn-primary';
    document.getElementById('dialog-confirm').onclick = () => {
        closeDialog();
        document.getElementById('backup-file-input').click();
    };
    document.getElementById('dialog-overlay').style.display = '';
}

async function handleBackupFile(event) {
    const file = event.target.files[0];
    if (!file) return;
    const status = document.getElementById('backup-status');
    status.innerHTML = '<span class="material-icons-round" style="color:var(--primary);vertical-align:middle;margin-right:4px;font-size:16px">hourglass_top</span>Restauration en cours...';
    status.className = 'settings-status';
    try {
        const text = await file.text();
        JSON.parse(text); // validate JSON
        const res = await fetch('/api/restore', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: text,
        });
        if (!res.ok) {
            const err = await res.json().catch(() => ({}));
            throw new Error(err.error || 'Erreur serveur: ' + res.status);
        }
        status.innerHTML = '<span class="material-icons-round" style="color:var(--success);vertical-align:middle;margin-right:4px;font-size:16px">check_circle</span>Donn\u00e9es restaur\u00e9es avec succ\u00e8s';
        status.className = 'settings-status settings-status-ok';
        showToast('Donn\u00e9es restaur\u00e9es \u2014 rechargement...');
        // Reload data
        setTimeout(() => {
            loadLibrary();
            loadPlaylist();
            loadNewEpisodes();
            loadHistory();
            loadSettings();
        }, 500);
    } catch (e) {
        status.innerHTML = '<span class="material-icons-round" style="color:var(--error);vertical-align:middle;margin-right:4px;font-size:16px">error</span>Erreur: ' + e.message;
        status.className = 'settings-status settings-status-warn';
        showToast('Erreur: ' + e.message);
    }
    // Reset file input so same file can be re-selected
    event.target.value = '';
}

// ========================
// Utility
// ========================
let currentTab = 'library';
let allTags = [];
let selectedTagId = null;
let allPodcasts = [];
let searchDebounceTimer = null;
let dialogCallback = null;
let showHidden = false;

// Podcast/Episode detail state
let currentPodcastDetail = null;
let currentPodcastEpisodes = [];
let episodeFilter = 'all';
let currentEpisodeDetail = null;
let currentEpisodeArtwork = '';
let currentEpisodePodcastTitle = '';

// New episodes state
let newEpisodesSelectedTagId = null;

// Playlist state
let playlistDownloadedOnly = false;

// Player state
let playerEpisode = null;
let playerArtwork = '';
let playerPodcastTitle = '';
let isPlaying = false;
let playbackSpeed = 1.0;
let positionSyncInterval = null;
const SPEEDS = [0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0];

// Volume normalization (Web Audio API)
let volumeNormEnabled = localStorage.getItem('volumeNormEnabled') === 'true';
let audioContext = null;
let sourceNode = null;
let compressorNode = null;
let gainNode = null;

// Navigation stack for back navigation
let navStack = [];

// ========================
// Init
// ========================
document.addEventListener('DOMContentLoaded', () => {
    // Tab switching
    document.querySelectorAll('.tab').forEach(tab => {
        tab.addEventListener('click', () => switchTab(tab.dataset.tab));
    });

    // Search input with debounce
    const searchInput = document.getElementById('search-input');
    searchInput.addEventListener('input', () => {
        clearTimeout(searchDebounceTimer);
        searchDebounceTimer = setTimeout(() => {
            const query = searchInput.value.trim();
            if (query.length >= 2) {
                performSearch(query);
            } else {
                document.getElementById('search-results').innerHTML = '';
                document.getElementById('search-ai-results').style.display = 'none';
                document.getElementById('search-itunes-header').style.display = 'none';
                document.getElementById('search-empty').style.display = '';
                document.getElementById('search-loading').style.display = 'none';
            }
        }, 400);
    });

    // Country select triggers re-search
    document.getElementById('country-select').addEventListener('change', () => {
        const query = searchInput.value.trim();
        if (query.length >= 2) performSearch(query);
    });

    // Audio element events
    const audio = document.getElementById('audio-element');
    audio.addEventListener('timeupdate', onAudioTimeUpdate);
    audio.addEventListener('ended', onAudioEnded);
    audio.addEventListener('play', () => { isPlaying = true; updatePlayButton(); });
    audio.addEventListener('pause', () => { isPlaying = false; updatePlayButton(); });
    audio.addEventListener('loadedmetadata', onAudioLoaded);

    // Progress bar seek
    const progressInput = document.getElementById('player-progress-input');
    progressInput.addEventListener('input', onProgressSeek);

    // Login password enter key
    document.getElementById('login-password').addEventListener('keydown', (e) => {
        if (e.key === 'Enter') submitLogin();
    });

    // Check authentication before loading
    checkAuth().then(authenticated => {
        if (authenticated) loadLibrary();
    });
});

// ========================
// Tabs & Navigation
// ========================
function switchTab(tabName) {
    // If navigating away from detail views, clear them
    navStack = [];
    currentTab = tabName;
    showPage(tabName);

    document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
    const tabBtn = document.querySelector(`.tab[data-tab="${tabName}"]`);
    if (tabBtn) tabBtn.classList.add('active');

    if (tabName === 'library') loadLibrary();
    if (tabName === 'new-episodes') loadNewEpisodes();
    if (tabName === 'playlist') loadPlaylist();
    if (tabName === 'history') loadHistory();
    if (tabName === 'settings') loadSettings();
}

function showPage(pageId) {
    document.querySelectorAll('.page').forEach(p => p.classList.remove('active'));
    const page = document.getElementById(pageId);
    if (page) page.classList.add('active');
}

// ========================
// Library
// ========================
async function loadLibrary() {
    try {
        const [podcastsRes, tagsRes] = await Promise.all([
            fetch('/api/podcasts'),
            fetch('/api/tags'),
        ]);
        allPodcasts = await podcastsRes.json();
        allTags = await tagsRes.json();
        renderTagsFilter();
        renderLibrary(allPodcasts);
    } catch (e) {
        showToast('Erreur de chargement: ' + e.message);
    }
}

function renderTagsFilter() {
    const container = document.getElementById('tags-filter');
    if (allTags.length === 0) { container.innerHTML = ''; return; }

    let html = `<button class="tag-chip ${selectedTagId === null ? 'active' : ''}" onclick="filterByTag(null)">Tous</button>`;
    allTags.forEach(tag => {
        html += `<button class="tag-chip ${selectedTagId === tag.id ? 'active' : ''}" onclick="filterByTag(${tag.id})">${esc(tag.name)}</button>`;
    });
    html += `<button class="tag-chip" onclick="openTagManager()" style="border-style:dashed">+ G&eacute;rer</button>`;
    container.innerHTML = html;
}

function filterByTag(tagId) {
    selectedTagId = tagId;
    renderTagsFilter();
    if (tagId === null) {
        renderLibrary(allPodcasts);
    } else {
        renderLibrary(allPodcasts.filter(p => p.tags && p.tags.some(t => t.id === tagId)));
    }
}

function toggleShowHidden() {
    showHidden = !showHidden;
    if (selectedTagId === null) {
        renderLibrary(allPodcasts);
    } else {
        renderLibrary(allPodcasts.filter(p => p.tags && p.tags.some(t => t.id === selectedTagId)));
    }
}

async function toggleHidden(podcastId, hidden, title) {
    try {
        await fetch(`/api/podcasts/${podcastId}/hidden`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ hidden }),
        });
        // Update local state
        const p = allPodcasts.find(p => p.id === podcastId);
        if (p) p.hidden = hidden;
        showToast(hidden ? `"${title}" cach\u00e9` : `"${title}" affich\u00e9`);
        if (selectedTagId === null) {
            renderLibrary(allPodcasts);
        } else {
            renderLibrary(allPodcasts.filter(p => p.tags && p.tags.some(t => t.id === selectedTagId)));
        }
    } catch (e) {
        showToast('Erreur: ' + e.message);
    }
}

function renderLibrary(podcasts) {
    const grid = document.getElementById('podcasts-grid');
    const empty = document.getElementById('library-empty');
    const hiddenToggle = document.getElementById('hidden-toggle');

    // Count hidden podcasts
    const hiddenCount = podcasts.filter(p => p.hidden).length;

    // Show/hide eye toggle button
    if (hiddenCount > 0) {
        hiddenToggle.style.display = '';
        hiddenToggle.querySelector('.material-icons-round').textContent = showHidden ? 'visibility' : 'visibility_off';
        hiddenToggle.title = showHidden ? 'Masquer les podcasts cach\u00e9s' : 'Afficher les podcasts cach\u00e9s';
    } else {
        hiddenToggle.style.display = 'none';
    }

    // Filter hidden unless showHidden is true
    const displayPodcasts = showHidden ? podcasts : podcasts.filter(p => !p.hidden);
    document.getElementById('podcast-count').textContent = displayPodcasts.length;

    if (displayPodcasts.length === 0) {
        grid.innerHTML = '';
        empty.style.display = '';
        return;
    }
    empty.style.display = 'none';

    grid.innerHTML = displayPodcasts.map(p => {
        const threeMonthsAgo = Date.now() - 90 * 24 * 60 * 60 * 1000;
        const isStale = p.latestEpisodeTimestamp > 0 && p.latestEpisodeTimestamp < threeMonthsAgo;
        const hiddenClass = p.hidden ? ' hidden' : '';
        return `
        <div class="podcast-card ${isStale ? 'stale' : ''}${hiddenClass}" onclick="openPodcastDetail(${p.id})">
            ${p.hidden ? '<div class="hidden-badge"><span class="material-icons-round">visibility_off</span></div>' : ''}
            ${p.sourceType === 'youtube' ? '<div class="youtube-badge"><span class="material-icons-round" style="color:#f00">smart_display</span></div>' : ''}
            <div class="card-content">
                <img class="artwork" src="${esc(p.artworkUrl)}" alt="${esc(p.title)}" onerror="this.src='${placeholderImg()}'">
                <div class="info">
                    <div class="title">${esc(p.title)}</div>
                    <div class="author">${esc(p.author)}</div>
                    <div class="card-tags">
                        ${(p.tags || []).map(t => `<span class="card-tag">${esc(t.name)}</span>`).join('')}
                    </div>
                </div>
            </div>
            <div class="card-actions">
                <button onclick="event.stopPropagation(); toggleHidden(${p.id}, ${!p.hidden}, '${escJs(p.title)}')">
                    <span class="material-icons-round" style="font-size:18px">${p.hidden ? 'visibility' : 'visibility_off'}</span>
                    ${p.hidden ? 'Afficher' : 'Cacher'}
                </button>
                <button onclick="event.stopPropagation(); showTagAssign(${p.id}, '${escJs(p.title)}')">
                    <span class="material-icons-round" style="font-size:18px">label</span>
                    Tags
                </button>
                <button class="danger" onclick="event.stopPropagation(); confirmUnsubscribe(${p.id}, '${escJs(p.title)}')">
                    <span class="material-icons-round" style="font-size:18px">delete_outline</span>
                    Supprimer
                </button>
            </div>
        </div>`;
    }).join('');
}

// ========================
// Podcast Detail
// ========================

// Store search result info for subscribe-from-detail
let previewSearchInfo = null;

async function openPodcastDetail(podcastId) {
    navStack.push(currentTab);
    showPage('podcast-detail');
    previewSearchInfo = null;

    // Remove active tab highlight
    document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));

    preparePodcastDetailUI();
    await loadPodcastDetailData(podcastId);
}

function preparePodcastDetailUI() {
    document.getElementById('episodes-list').innerHTML = '';
    document.getElementById('episodes-loading').style.display = '';
    document.getElementById('detail-subscribe-bar').style.display = 'none';
    document.getElementById('detail-tags').innerHTML = '';
}

async function loadPodcastDetailData(podcastId) {
    try {
        const [podcastRes, episodesRes] = await Promise.all([
            fetch(`/api/podcasts/${podcastId}`),
            fetch(`/api/podcasts/${podcastId}/episodes`),
        ]);
        currentPodcastDetail = await podcastRes.json();
        currentPodcastEpisodes = await episodesRes.json();

        document.getElementById('detail-artwork').src = currentPodcastDetail.artworkUrl || '';
        document.getElementById('detail-title').textContent = currentPodcastDetail.title;
        document.getElementById('detail-author').textContent = currentPodcastDetail.author;
        document.getElementById('detail-description').innerHTML = sanitizeHtml(currentPodcastDetail.description || '');

        renderDetailTags(currentPodcastDetail);

        episodeFilter = 'all';
        document.getElementById('filter-all').classList.add('active');
        document.getElementById('filter-unplayed').classList.remove('active');
        renderEpisodes();
    } catch (e) {
        showToast('Erreur: ' + e.message);
    } finally {
        document.getElementById('episodes-loading').style.display = 'none';
    }
}

async function openSearchPodcastDetail(collectionId, collectionName, artistName, artworkUrl, feedUrl, alreadySubscribed) {
    navStack.push('search');
    showPage('podcast-detail');

    document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));

    document.getElementById('episodes-list').innerHTML = '';
    document.getElementById('episodes-loading').style.display = '';

    // Show podcast info immediately with what we have
    document.getElementById('detail-artwork').src = artworkUrl || '';
    document.getElementById('detail-title').textContent = collectionName;
    document.getElementById('detail-author').textContent = artistName;
    document.getElementById('detail-description').textContent = '';
    document.getElementById('detail-tags').innerHTML = '';

    // Store search info for potential subscribe
    previewSearchInfo = { collectionId, collectionName, artistName, artworkUrl, feedUrl };

    try {
        const res = await fetch('/api/preview', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ collectionId, collectionName, artistName, artworkUrl, feedUrl }),
        });
        const data = await res.json();

        if (data.error) {
            showToast(data.error);
            document.getElementById('episodes-loading').style.display = 'none';
            return;
        }

        currentPodcastDetail = data.podcast;
        currentPodcastEpisodes = data.episodes;

        document.getElementById('detail-artwork').src = currentPodcastDetail.artworkUrl || artworkUrl || '';
        document.getElementById('detail-title').textContent = currentPodcastDetail.title;
        document.getElementById('detail-author').textContent = currentPodcastDetail.author;
        document.getElementById('detail-description').innerHTML = sanitizeHtml(currentPodcastDetail.description || '');

        renderDetailTags(currentPodcastDetail);

        // Show subscribe button if not subscribed
        if (!currentPodcastDetail.subscribed) {
            document.getElementById('detail-subscribe-bar').style.display = '';
            document.getElementById('detail-subscribe-btn').innerHTML = '<span class="material-icons-round">add_circle_outline</span> S\'abonner';
            document.getElementById('detail-subscribe-btn').disabled = false;
        } else {
            document.getElementById('detail-subscribe-bar').style.display = 'none';
        }

        episodeFilter = 'all';
        document.getElementById('filter-all').classList.add('active');
        document.getElementById('filter-unplayed').classList.remove('active');
        renderEpisodes();
    } catch (e) {
        showToast('Erreur: ' + e.message);
    } finally {
        document.getElementById('episodes-loading').style.display = 'none';
    }
}

async function subscribeFromDetail() {
    if (!previewSearchInfo) return;
    const btn = document.getElementById('detail-subscribe-btn');
    btn.disabled = true;
    btn.innerHTML = '<div class="spinner" style="width:18px;height:18px;border-width:2px;margin:0;display:inline-block"></div> Abonnement...';

    try {
        const res = await fetch('/api/subscribe', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                collectionId: previewSearchInfo.collectionId,
                collectionName: previewSearchInfo.collectionName,
                artistName: previewSearchInfo.artistName,
                artworkUrl: previewSearchInfo.artworkUrl,
                feedUrl: previewSearchInfo.feedUrl,
            }),
        });

        if (res.ok) {
            btn.innerHTML = '<span class="material-icons-round">check</span> Abonn\u00e9';
            showToast('Abonn\u00e9 \u00e0 ' + previewSearchInfo.collectionName);
            if (currentPodcastDetail) {
                currentPodcastDetail.subscribed = true;
                currentPodcastDetail.tags = [];
                renderDetailTags(currentPodcastDetail);
            }
        } else {
            const err = await res.json();
            throw new Error(err.error || 'Erreur');
        }
    } catch (e) {
        btn.disabled = false;
        btn.innerHTML = '<span class="material-icons-round">add_circle_outline</span> R\u00e9essayer';
        showToast('Erreur: ' + e.message);
    }
}

function closePodcastDetail() {
    const prev = navStack.pop() || 'library';
    if (prev === 'episode-detail') {
        showPage('episode-detail');
    } else {
        switchTab(prev);
    }
}

function renderDetailTags(podcast) {
    const container = document.getElementById('detail-tags');
    if (!podcast || !podcast.subscribed) {
        container.innerHTML = '';
        return;
    }

    const tags = podcast.tags || [];
    let html = '';

    tags.forEach(tag => {
        html += `<span class="detail-tag-chip">${esc(tag.name)}</span>`;
    });

    html += `<button class="detail-tag-manage" onclick="showDetailTagAssign()" title="Gérer les tags">
        <span class="material-icons-round">label</span>
        ${tags.length === 0 ? 'Tags' : ''}
    </button>`;

    container.innerHTML = html;
}

async function showDetailTagAssign() {
    if (!currentPodcastDetail || !currentPodcastDetail.id) return;
    const podcastId = currentPodcastDetail.id;

    // Refresh tags list
    try {
        const tagsRes = await fetch('/api/tags');
        allTags = await tagsRes.json();
    } catch (e) { /* use cached */ }

    // Get current podcast tags
    let podcastTagIds = (currentPodcastDetail.tags || []).map(t => t.id);
    try {
        const res = await fetch(`/api/podcasts/${podcastId}/tags`);
        const ptags = await res.json();
        podcastTagIds = ptags.map(t => t.id);
    } catch (e) { /* use cached */ }

    document.getElementById('dialog-title').textContent = 'Tags — ' + (currentPodcastDetail.title || '');

    let html = '<div style="margin-bottom:12px">';
    html += '<div style="display:flex;gap:8px;margin-bottom:12px">';
    html += '<input type="text" id="detail-new-tag-input" placeholder="Nouveau tag..." style="flex:1;padding:8px 12px;border:1px solid var(--outline);border-radius:var(--radius-xs);font-family:inherit;font-size:14px;outline:none">';
    html += '<button onclick="createAndAssignDetailTag()" style="padding:8px 16px;background:var(--primary);color:white;border:none;border-radius:var(--radius-xs);cursor:pointer;font-family:inherit;font-weight:500">Créer</button>';
    html += '</div>';

    html += '<div style="display:flex;flex-direction:column;gap:8px;max-height:300px;overflow-y:auto">';
    if (allTags.length === 0) {
        html += '<p style="color:var(--on-surface-variant)">Aucun tag. Créez-en ci-dessus.</p>';
    } else {
        allTags.forEach(tag => {
            const checked = podcastTagIds.includes(tag.id) ? 'checked' : '';
            html += `<label style="display:flex;align-items:center;gap:8px;cursor:pointer;padding:6px 0">
                <input type="checkbox" ${checked} onchange="toggleDetailTag(${podcastId}, ${tag.id}, this.checked)" style="width:18px;height:18px;accent-color:var(--primary)">
                <span>${esc(tag.name)}</span>
            </label>`;
        });
    }
    html += '</div></div>';

    document.getElementById('dialog-text').innerHTML = html;
    document.getElementById('dialog-confirm').textContent = 'Fermer';
    document.getElementById('dialog-confirm').className = 'btn-text';
    document.getElementById('dialog-confirm').onclick = () => { closeDialog(); refreshDetailTags(); };
    document.getElementById('dialog-overlay').style.display = '';
}

async function toggleDetailTag(podcastId, tagId, add) {
    try {
        if (add) {
            await fetch(`/api/podcasts/${podcastId}/tags/${tagId}`, { method: 'POST' });
        } else {
            await fetch(`/api/podcasts/${podcastId}/tags/${tagId}`, { method: 'DELETE' });
        }
    } catch (e) {
        showToast('Erreur: ' + e.message);
    }
}

async function createAndAssignDetailTag() {
    const input = document.getElementById('detail-new-tag-input');
    const name = input.value.trim();
    if (!name || !currentPodcastDetail) return;

    try {
        // Create the tag
        const res = await fetch('/api/tags', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ name }),
        });
        if (res.ok) {
            const newTag = await res.json();
            // Assign it to the podcast
            await fetch(`/api/podcasts/${currentPodcastDetail.id}/tags/${newTag.id}`, { method: 'POST' });
            input.value = '';
            showToast('Tag "' + name + '" créé et assigné');
            // Re-open the dialog to refresh
            showDetailTagAssign();
        }
    } catch (e) {
        showToast('Erreur: ' + e.message);
    }
}

async function refreshDetailTags() {
    if (!currentPodcastDetail || !currentPodcastDetail.id) return;
    try {
        const res = await fetch(`/api/podcasts/${currentPodcastDetail.id}/tags`);
        const tags = await res.json();
        currentPodcastDetail.tags = tags;
        renderDetailTags(currentPodcastDetail);
    } catch (e) { /* ignore */ }
}

function filterEpisodes(filter) {
    episodeFilter = filter;
    document.getElementById('filter-all').classList.toggle('active', filter === 'all');
    document.getElementById('filter-unplayed').classList.toggle('active', filter === 'unplayed');
    renderEpisodes();
}

function renderEpisodes() {
    let episodes = currentPodcastEpisodes;
    if (episodeFilter === 'unplayed') {
        episodes = episodes.filter(e => !e.played);
    }

    const list = document.getElementById('episodes-list');
    if (episodes.length === 0) {
        list.innerHTML = '<div class="empty-state"><p>Aucun episode</p></div>';
        return;
    }

    list.innerHTML = episodes.map(e => {
        const isNowPlaying = playerEpisode && playerEpisode.id === e.id;
        const progressPct = e.duration > 0 ? Math.round((e.playbackPosition / (e.duration * 1000)) * 100) : 0;
        const dateStr = e.pubDateTimestamp > 0 ? new Date(e.pubDateTimestamp).toLocaleDateString() : e.pubDate;
        const durationStr = formatDuration(e.duration);
        const downloadIcon = e.downloadPath ? '<span class="material-icons-round download-indicator" title="T\u00e9l\u00e9charg\u00e9">download_done</span>' : '';
        const ytBadge = (e.sourceType === 'youtube' || (currentPodcastDetail && currentPodcastDetail.sourceType === 'youtube')) ? '<span class="yt-badge-sm">YT</span>' : '';

        return `
        <div class="episode-item ${e.played ? 'played' : ''} ${isNowPlaying ? 'now-playing' : ''}" onclick="openEpisodeDetail(${e.id})">
            <button class="episode-play-btn" onclick="event.stopPropagation(); playEpisode(${e.id})" title="Lire">
                <span class="material-icons-round">${isNowPlaying && isPlaying ? 'pause' : 'play_arrow'}</span>
            </button>
            <div class="episode-info">
                <div class="episode-title">${esc(e.title)}${downloadIcon}${ytBadge}</div>
                <div class="episode-meta">
                    <span>${dateStr}</span>
                    ${durationStr ? `<span>${durationStr}</span>` : ''}
                    ${progressPct > 0 && progressPct < 100 ? `
                        <div class="episode-progress">
                            <div class="episode-progress-fill" style="width:${progressPct}%"></div>
                        </div>
                    ` : ''}
                </div>
            </div>
            <div class="episode-actions">
                <button class="episode-action-btn" onclick="event.stopPropagation(); togglePlaylistEpisode(${e.id}, this)" title="Playlist">
                    <span class="material-icons-round">playlist_add</span>
                </button>
            </div>
        </div>`;
    }).join('');
}

// ========================
// Episode Detail
// ========================
async function openEpisodeDetail(episodeId) {
    navStack.push('podcast-detail');
    showPage('episode-detail');

    try {
        const res = await fetch(`/api/episodes/${episodeId}`);
        currentEpisodeDetail = await res.json();
        currentEpisodeArtwork = currentEpisodeDetail.artworkUrl || (currentPodcastDetail ? currentPodcastDetail.artworkUrl : '');
        currentEpisodePodcastTitle = currentPodcastDetail ? currentPodcastDetail.title : '';

        document.getElementById('ep-detail-artwork').src = currentEpisodeArtwork;
        document.getElementById('ep-detail-title').textContent = currentEpisodeDetail.title;
        document.getElementById('ep-detail-podcast').textContent = currentEpisodePodcastTitle;
        document.getElementById('ep-detail-date').textContent = currentEpisodeDetail.pubDateTimestamp > 0
            ? new Date(currentEpisodeDetail.pubDateTimestamp).toLocaleDateString()
            : currentEpisodeDetail.pubDate;

        // Description (allow HTML)
        document.getElementById('ep-detail-description').innerHTML = sanitizeHtml(currentEpisodeDetail.description || '');

        updateEpisodeDetailButtons();
        loadBookmarks(episodeId);
    } catch (e) {
        showToast('Erreur: ' + e.message);
    }
}

function closeEpisodeDetail() {
    const prev = navStack.pop();
    if (prev === 'podcast-detail') {
        showPage('podcast-detail');
    } else if (prev === 'playlist') {
        showPage('playlist');
        document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
        document.querySelector('.tab[data-tab="playlist"]').classList.add('active');
    } else if (prev === 'new-episodes') {
        showPage('new-episodes');
        document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
        document.querySelector('.tab[data-tab="new-episodes"]').classList.add('active');
    } else if (prev === 'history') {
        showPage('history');
        document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
        document.querySelector('.tab[data-tab="history"]').classList.add('active');
    } else {
        switchTab(prev || 'library');
    }
}

function goToPodcastFromEpisode() {
    if (!currentEpisodeDetail || !currentEpisodeDetail.podcastId) return;
    // Push episode-detail so closePodcastDetail can return here
    navStack.push('episode-detail');
    showPage('podcast-detail');
    previewSearchInfo = null;
    document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
    preparePodcastDetailUI();
    loadPodcastDetailData(currentEpisodeDetail.podcastId);
}

function updateEpisodeDetailButtons() {
    if (!currentEpisodeDetail) return;
    const e = currentEpisodeDetail;

    // Play button
    const isNowPlaying = playerEpisode && playerEpisode.id === e.id;
    const playBtn = document.getElementById('ep-play-btn');
    playBtn.innerHTML = isNowPlaying && isPlaying
        ? '<span class="material-icons-round">pause</span> Pause'
        : '<span class="material-icons-round">play_arrow</span> Lire';

    // Played button
    const playedBtn = document.getElementById('ep-played-btn');
    playedBtn.innerHTML = e.played
        ? '<span class="material-icons-round">check_circle</span> Lu'
        : '<span class="material-icons-round">check_circle_outline</span> Non lu';
    playedBtn.classList.toggle('active', e.played);
}

function playCurrentEpisode() {
    if (!currentEpisodeDetail) return;
    if (playerEpisode && playerEpisode.id === currentEpisodeDetail.id) {
        playerTogglePlay();
    } else {
        playEpisode(currentEpisodeDetail.id);
    }
}

async function togglePlaylistCurrentEpisode() {
    if (!currentEpisodeDetail) return;
    await togglePlaylistEpisode(currentEpisodeDetail.id);
}

async function togglePlayedCurrentEpisode() {
    if (!currentEpisodeDetail) return;
    try {
        if (currentEpisodeDetail.played) {
            await fetch(`/api/episodes/${currentEpisodeDetail.id}/unplayed`, { method: 'PUT' });
            currentEpisodeDetail.played = false;
        } else {
            await fetch(`/api/episodes/${currentEpisodeDetail.id}/played`, { method: 'PUT' });
            currentEpisodeDetail.played = true;
        }
        updateEpisodeDetailButtons();
        showToast(currentEpisodeDetail.played ? 'Marqu\u00e9 comme lu' : 'Marqu\u00e9 comme non lu');
    } catch (e) {
        showToast('Erreur: ' + e.message);
    }
}

// ========================
// Bookmarks
// ========================
async function loadBookmarks(episodeId) {
    try {
        const res = await fetch(`/api/episodes/${episodeId}/bookmarks`);
        const bookmarks = await res.json();
        const list = document.getElementById('bookmarks-list');

        if (bookmarks.length === 0) {
            list.innerHTML = '<p style="color:var(--on-surface-variant);font-size:13px;padding:8px 0">Aucun signet</p>';
            return;
        }

        list.innerHTML = bookmarks.map(b => `
            <div class="bookmark-item">
                <span class="bookmark-time" onclick="seekToBookmark(${b.positionMs})">${formatTime(b.positionMs / 1000)}</span>
                <span class="bookmark-comment">${esc(b.comment) || '—'}</span>
                <button class="bookmark-delete" onclick="deleteBookmark(${b.id})" title="Supprimer">
                    <span class="material-icons-round">close</span>
                </button>
            </div>
        `).join('');
    } catch (e) {
        console.error('Failed to load bookmarks', e);
    }
}

function seekToBookmark(positionMs) {
    if (!currentEpisodeDetail) return;
    // Start playing this episode if not already, then seek
    if (!playerEpisode || playerEpisode.id !== currentEpisodeDetail.id) {
        playEpisode(currentEpisodeDetail.id, positionMs / 1000);
    } else {
        const audio = document.getElementById('audio-element');
        audio.currentTime = positionMs / 1000;
    }
}

function addBookmarkDialog() {
    if (!currentEpisodeDetail) return;
    const audio = document.getElementById('audio-element');
    const currentPos = (playerEpisode && playerEpisode.id === currentEpisodeDetail.id)
        ? Math.round(audio.currentTime * 1000)
        : currentEpisodeDetail.playbackPosition;

    document.getElementById('dialog-title').textContent = 'Ajouter un signet';
    document.getElementById('dialog-text').innerHTML = `
        <div style="margin-bottom:16px">
            <label style="font-size:13px;color:var(--on-surface-variant);display:block;margin-bottom:4px">Position: ${formatTime(currentPos / 1000)}</label>
            <input type="text" id="bookmark-comment-input" placeholder="Commentaire (optionnel)..." style="width:100%;padding:10px 12px;border:1px solid var(--outline);border-radius:var(--radius-xs);font-family:inherit;font-size:14px;outline:none">
        </div>
    `;
    document.getElementById('dialog-confirm').textContent = 'Ajouter';
    document.getElementById('dialog-confirm').className = 'btn-primary';
    document.getElementById('dialog-confirm').onclick = async () => {
        const comment = document.getElementById('bookmark-comment-input').value.trim();
        closeDialog();
        try {
            await fetch(`/api/episodes/${currentEpisodeDetail.id}/bookmarks`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ positionMs: currentPos, comment }),
            });
            showToast('Signet ajout\u00e9');
            loadBookmarks(currentEpisodeDetail.id);
        } catch (e) {
            showToast('Erreur: ' + e.message);
        }
    };
    document.getElementById('dialog-overlay').style.display = '';
}

async function deleteBookmark(bookmarkId) {
    try {
        await fetch(`/api/bookmarks/${bookmarkId}`, { method: 'DELETE' });
        showToast('Signet supprim\u00e9');
        if (currentEpisodeDetail) loadBookmarks(currentEpisodeDetail.id);
    } catch (e) {
        showToast('Erreur: ' + e.message);
    }
}

// ========================
// Playlist
// ========================
async function loadPlaylist() {
    try {
        const [res, tagsRes] = await Promise.all([
            fetch('/api/playlist'),
            fetch('/api/tags'),
        ]);
        const items = await res.json();
        allTags = await tagsRes.json();
        const container = document.getElementById('playlist-items');
        const empty = document.getElementById('playlist-empty');
        if (items.length === 0) {
            container.innerHTML = '';
            empty.style.display = '';
            document.getElementById('playlist-count').textContent = 0;
            return;
        }
        empty.style.display = 'none';

        // Apply downloaded-only filter
        let filteredItems = items;
        if (playlistDownloadedOnly) {
            filteredItems = items.filter(item => !!item.downloadPath);
        }
        // Update filter button state
        const filterBtn = document.getElementById('playlist-download-filter');
        if (filterBtn) {
            filterBtn.classList.toggle('active', playlistDownloadedOnly);
        }

        document.getElementById('playlist-count').textContent = filteredItems.length;

        if (filteredItems.length === 0) {
            container.innerHTML = '';
            empty.style.display = '';
            return;
        }

        container.innerHTML = filteredItems.map(item => {
            const isNowPlaying = playerEpisode && playerEpisode.id === item.id;
            const progressPct = item.duration > 0 ? Math.round((item.playbackPosition / (item.duration * 1000)) * 100) : 0;
            const downloadIcon = item.downloadPath ? '<span class="material-icons-round download-indicator" title="Téléchargé">download_done</span>' : '';
            const ytBadge = item.sourceType === 'youtube' ? '<span class="yt-badge-sm">YT</span>' : '';
            const artworkYtOverlay = item.sourceType === 'youtube' ? '<span class="yt-badge-overlay">YT</span>' : '';

            return `
            <div class="playlist-item ${isNowPlaying ? 'now-playing' : ''}" draggable="true" data-episode-id="${item.id}">
                <span class="drag-handle material-icons-round">drag_indicator</span>
                <div class="artwork-wrapper" onclick="openPlaylistEpisodeDetail(${item.id}, '${escJs(item.artworkUrl)}', '${escJs(item.podcastTitle)}')" style="cursor:pointer">
                    <img class="playlist-item-artwork" src="${esc(item.artworkUrl)}" alt="" onerror="this.src='${placeholderImg()}'">
                    ${artworkYtOverlay}
                </div>
                <div class="playlist-item-info" onclick="openPlaylistEpisodeDetail(${item.id}, '${escJs(item.artworkUrl)}', '${escJs(item.podcastTitle)}')" style="cursor:pointer">
                    <div class="playlist-item-title">${esc(item.title)}${downloadIcon}${ytBadge}</div>
                    <div class="playlist-item-podcast">${esc(item.podcastTitle)}</div>
                </div>
                <div class="playlist-item-progress">
                    <div class="episode-progress" style="max-width:100%">
                        <div class="episode-progress-fill" style="width:${progressPct}%"></div>
                    </div>
                </div>
                <div class="playlist-item-actions">
                    <button class="episode-action-btn" onclick="playEpisodeFromPlaylist(${item.id}, '${escJs(item.artworkUrl)}', '${escJs(item.podcastTitle)}')" title="Lire">
                        <span class="material-icons-round">${isNowPlaying && isPlaying ? 'pause' : 'play_arrow'}</span>
                    </button>
                    <button class="episode-action-btn" onclick="removeFromPlaylist(${item.id})" title="Retirer">
                        <span class="material-icons-round">remove_circle_outline</span>
                    </button>
                </div>
            </div>`;
        }).join('');

        initPlaylistDragDrop();
    } catch (e) {
        showToast('Erreur playlist: ' + e.message);
    }
}

function togglePlaylistDownloadFilter() {
    playlistDownloadedOnly = !playlistDownloadedOnly;
    loadPlaylist();
}

// ========================
// Playlist Drag & Drop
// ========================
let draggedItem = null;

function initPlaylistDragDrop() {
    const container = document.getElementById('playlist-items');
    const items = container.querySelectorAll('.playlist-item[draggable]');

    items.forEach(item => {
        item.addEventListener('dragstart', onDragStart);
        item.addEventListener('dragend', onDragEnd);
        item.addEventListener('dragover', onDragOver);
        item.addEventListener('dragenter', onDragEnter);
        item.addEventListener('dragleave', onDragLeave);
        item.addEventListener('drop', onDrop);
    });
}

function onDragStart(e) {
    draggedItem = this;
    this.classList.add('dragging');
    e.dataTransfer.effectAllowed = 'move';
    e.dataTransfer.setData('text/plain', this.dataset.episodeId);
}

function onDragEnd(e) {
    this.classList.remove('dragging');
    document.querySelectorAll('.playlist-item.drag-over').forEach(el => el.classList.remove('drag-over'));
    draggedItem = null;
}

function onDragOver(e) {
    e.preventDefault();
    e.dataTransfer.dropEffect = 'move';
}

function onDragEnter(e) {
    e.preventDefault();
    if (this !== draggedItem) {
        this.classList.add('drag-over');
    }
}

function onDragLeave(e) {
    this.classList.remove('drag-over');
}

async function onDrop(e) {
    e.preventDefault();
    this.classList.remove('drag-over');
    if (!draggedItem || this === draggedItem) return;

    const container = document.getElementById('playlist-items');
    const allItems = [...container.querySelectorAll('.playlist-item[draggable]')];
    const fromIndex = allItems.indexOf(draggedItem);
    const toIndex = allItems.indexOf(this);

    // Move the dragged element in the DOM
    if (fromIndex < toIndex) {
        container.insertBefore(draggedItem, this.nextSibling);
    } else {
        container.insertBefore(draggedItem, this);
    }

    // Collect new order and save
    const newOrder = [...container.querySelectorAll('.playlist-item[draggable]')].map(
        el => parseInt(el.dataset.episodeId)
    );
    try {
        await fetch('/api/playlist/reorder', {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ episodeIds: newOrder }),
        });
    } catch (err) {
        showToast('Erreur réorganisation: ' + err.message);
        loadPlaylist(); // reload on error
    }
}

async function togglePlaylistEpisode(episodeId, btn) {
    try {
        // Try to add — if already in playlist, remove
        const checkRes = await fetch('/api/playlist');
        const items = await checkRes.json();
        const isIn = items.some(i => i.id === episodeId);

        if (isIn) {
            await fetch(`/api/playlist/${episodeId}`, { method: 'DELETE' });
            showToast('Retir\u00e9 de la playlist');
        } else {
            await fetch(`/api/playlist/${episodeId}`, { method: 'POST' });
            showToast('Ajout\u00e9 \u00e0 la playlist');
        }
    } catch (e) {
        showToast('Erreur: ' + e.message);
    }
}

async function removeFromPlaylist(episodeId) {
    try {
        await fetch(`/api/playlist/${episodeId}`, { method: 'DELETE' });
        showToast('Retir\u00e9 de la playlist');
        loadPlaylist();
    } catch (e) {
        showToast('Erreur: ' + e.message);
    }
}

async function openPlaylistEpisodeDetail(episodeId, artworkUrl, podcastTitle) {
    navStack.push('playlist');
    showPage('episode-detail');
    document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));

    try {
        const res = await fetch(`/api/episodes/${episodeId}`);
        currentEpisodeDetail = await res.json();
        currentEpisodeArtwork = artworkUrl || currentEpisodeDetail.artworkUrl || '';
        currentEpisodePodcastTitle = podcastTitle || '';

        // Try to get podcast detail
        const podcastRes = await fetch(`/api/podcasts/${currentEpisodeDetail.podcastId}`);
        if (podcastRes.ok) {
            currentPodcastDetail = await podcastRes.json();
            currentEpisodePodcastTitle = currentPodcastDetail.title;
        }

        document.getElementById('ep-detail-artwork').src = currentEpisodeArtwork;
        document.getElementById('ep-detail-title').textContent = currentEpisodeDetail.title;
        document.getElementById('ep-detail-podcast').textContent = currentEpisodePodcastTitle;
        document.getElementById('ep-detail-date').textContent = currentEpisodeDetail.pubDateTimestamp > 0
            ? new Date(currentEpisodeDetail.pubDateTimestamp).toLocaleDateString()
            : currentEpisodeDetail.pubDate;
        document.getElementById('ep-detail-description').innerHTML = sanitizeHtml(currentEpisodeDetail.description || '');

        updateEpisodeDetailButtons();
        loadBookmarks(currentEpisodeDetail.id);
    } catch (e) {
        showToast('Erreur: ' + e.message);
    }
}

async function autoAddPlaylist() {
    // If no tags, just auto-add all directly
    if (allTags.length === 0) {
        await doAutoAdd(null);
        return;
    }

    // Show tag selection dialog
    document.getElementById('dialog-title').textContent = 'Auto-ajout par tag';

    let html = '<div style="display:flex;flex-direction:column;gap:8px;max-height:300px;overflow-y:auto">';
    html += `<button class="btn-outline" onclick="closeDialog(); doAutoAdd(null)" style="width:100%;text-align:left;padding:10px 16px">
        <span class="material-icons-round" style="font-size:18px;vertical-align:middle;margin-right:8px">select_all</span>
        Tous les podcasts
    </button>`;
    allTags.forEach(tag => {
        html += `<button class="btn-outline" onclick="closeDialog(); doAutoAdd(${tag.id})" style="width:100%;text-align:left;padding:10px 16px">
            <span class="material-icons-round" style="font-size:18px;vertical-align:middle;margin-right:8px">label</span>
            ${esc(tag.name)}
        </button>`;
    });
    html += '</div>';

    document.getElementById('dialog-text').innerHTML = html;
    document.getElementById('dialog-confirm').style.display = 'none';
    document.getElementById('dialog-overlay').style.display = '';
}

async function doAutoAdd(tagId) {
    const btn = document.querySelector('.playlist-actions .btn-outline:first-child');
    if (btn) {
        btn.disabled = true;
        btn.innerHTML = '<div class="spinner" style="width:16px;height:16px;border-width:2px;margin:0;display:inline-block"></div> Ajout...';
    }
    try {
        let url = '/api/playlist/auto-add';
        if (tagId !== null) url += `?tagId=${tagId}`;
        await fetch(url, { method: 'POST' });
        showToast('Episodes ajout\u00e9s automatiquement');
        loadPlaylist();
    } catch (e) {
        showToast('Erreur: ' + e.message);
    } finally {
        if (btn) {
            btn.disabled = false;
            btn.innerHTML = '<span class="material-icons-round">auto_fix_high</span> Auto-ajout';
        }
    }
}

function clearPlaylistConfirm() {
    document.getElementById('dialog-title').textContent = 'Vider la playlist';
    document.getElementById('dialog-text').innerHTML = '<p>Voulez-vous vraiment vider toute la playlist ?</p>';
    document.getElementById('dialog-confirm').textContent = 'Vider';
    document.getElementById('dialog-confirm').className = 'btn-danger';
    document.getElementById('dialog-confirm').onclick = async () => {
        closeDialog();
        try {
            await fetch('/api/playlist', { method: 'DELETE' });
            showToast('Playlist vid\u00e9e');
            loadPlaylist();
        } catch (e) {
            showToast('Erreur: ' + e.message);
        }
    };
    document.getElementById('dialog-overlay').style.display = '';
}

// ========================
// New Episodes
// ========================
async function loadNewEpisodes() {
    const list = document.getElementById('new-episodes-list');
    const empty = document.getElementById('new-episodes-empty');
    const loading = document.getElementById('new-episodes-loading');
    const countBadge = document.getElementById('new-episodes-count');

    loading.style.display = '';
    list.innerHTML = '';
    empty.style.display = 'none';

    try {
        // Load tags for filter
        const tagsRes = await fetch('/api/tags');
        allTags = await tagsRes.json();
        renderNewEpisodesTagsFilter();

        let url = '/api/new-episodes';
        if (newEpisodesSelectedTagId !== null) {
            url += `?tagId=${newEpisodesSelectedTagId}`;
        }
        const res = await fetch(url);
        const episodes = await res.json();
        loading.style.display = 'none';
        countBadge.textContent = episodes.length;

        if (episodes.length === 0) {
            empty.style.display = '';
            return;
        }

        list.innerHTML = episodes.map(e => {
            const isNowPlaying = playerEpisode && playerEpisode.id === e.id;
            const progressPct = e.duration > 0 ? Math.round((e.playbackPosition / (e.duration * 1000)) * 100) : 0;
            const dateStr = e.pubDateTimestamp > 0 ? new Date(e.pubDateTimestamp).toLocaleDateString() : e.pubDate;
            const durationStr = formatDuration(e.duration);
            const downloadIcon = e.downloadPath ? '<span class="material-icons-round download-indicator" title="T\u00e9l\u00e9charg\u00e9">download_done</span>' : '';
            const ytBadge = e.sourceType === 'youtube' ? '<span class="yt-badge-sm">YT</span>' : '';
            const artworkYtOverlay = e.sourceType === 'youtube' ? '<span class="yt-badge-overlay">YT</span>' : '';

            return `
            <div class="episode-item-with-artwork ${e.played ? 'played' : ''} ${isNowPlaying ? 'now-playing' : ''}" onclick="openNewEpisodeDetail(${e.id}, '${escJs(e.artworkUrl)}')">
                <div class="artwork-wrapper">
                    <img class="episode-artwork-sm" src="${esc(e.artworkUrl)}" alt="" onerror="this.src='${placeholderImg()}'">
                    ${artworkYtOverlay}
                </div>
                <button class="episode-play-btn" onclick="event.stopPropagation(); playNewEpisode(${e.id}, '${escJs(e.artworkUrl)}')" title="Lire">
                    <span class="material-icons-round">${isNowPlaying && isPlaying ? 'pause' : 'play_arrow'}</span>
                </button>
                <div class="episode-info">
                    <div class="episode-title">${esc(e.title)}${downloadIcon}${ytBadge}</div>
                    <div class="episode-meta">
                        <span>${dateStr}</span>
                        ${durationStr ? `<span>${durationStr}</span>` : ''}
                        ${progressPct > 0 && progressPct < 100 ? `
                            <div class="episode-progress">
                                <div class="episode-progress-fill" style="width:${progressPct}%"></div>
                            </div>
                        ` : ''}
                    </div>
                </div>
                <div class="episode-actions">
                    <button class="episode-action-btn" onclick="event.stopPropagation(); togglePlaylistEpisode(${e.id})" title="Playlist">
                        <span class="material-icons-round">playlist_add</span>
                    </button>
                </div>
            </div>`;
        }).join('');
    } catch (e) {
        loading.style.display = 'none';
        showToast('Erreur: ' + e.message);
    }
}

function renderNewEpisodesTagsFilter() {
    const container = document.getElementById('new-episodes-tags');
    if (allTags.length === 0) { container.innerHTML = ''; return; }

    let html = `<button class="tag-chip ${newEpisodesSelectedTagId === null ? 'active' : ''}" onclick="filterNewEpisodesByTag(null)">Tous</button>`;
    allTags.forEach(tag => {
        html += `<button class="tag-chip ${newEpisodesSelectedTagId === tag.id ? 'active' : ''}" onclick="filterNewEpisodesByTag(${tag.id})">${esc(tag.name)}</button>`;
    });
    container.innerHTML = html;
}

function filterNewEpisodesByTag(tagId) {
    newEpisodesSelectedTagId = tagId;
    loadNewEpisodes();
}

async function playNewEpisode(episodeId, artworkUrl) {
    if (playerEpisode && playerEpisode.id === episodeId) {
        playerTogglePlay();
        return;
    }
    try {
        await saveCurrentPosition();
        const res = await fetch(`/api/episodes/${episodeId}`);
        const episode = await res.json();

        playerEpisode = episode;
        playerArtwork = artworkUrl || episode.artworkUrl || '';
        playerPodcastTitle = '';

        const audio = document.getElementById('audio-element');
        audio.src = await resolveAudioUrl(episode);
        audio.playbackRate = playbackSpeed;

        const startPos = episode.playbackPosition > 0 ? episode.playbackPosition / 1000 : 0;
        audio.currentTime = startPos;
        audio.play();

        showPlayerBar();
        startPositionSync();
        updatePlayerUI();
        addToHistory(episodeId);
        // Auto-add to top of playlist
        try { await fetch(`/api/playlist/${episodeId}/top`, { method: 'POST' }); } catch(e) {}
        loadNewEpisodes();
    } catch (e) {
        showToast('Erreur lecture: ' + e.message);
    }
}

async function openNewEpisodeDetail(episodeId, artworkUrl) {
    navStack.push('new-episodes');
    showPage('episode-detail');
    document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));

    try {
        const res = await fetch(`/api/episodes/${episodeId}`);
        currentEpisodeDetail = await res.json();
        currentEpisodeArtwork = artworkUrl || currentEpisodeDetail.artworkUrl || '';
        currentEpisodePodcastTitle = '';

        // Try to get podcast title
        const podcastRes = await fetch(`/api/podcasts/${currentEpisodeDetail.podcastId}`);
        if (podcastRes.ok) {
            const podcast = await podcastRes.json();
            currentPodcastDetail = podcast;
            currentEpisodePodcastTitle = podcast.title;
        }

        document.getElementById('ep-detail-artwork').src = currentEpisodeArtwork;
        document.getElementById('ep-detail-title').textContent = currentEpisodeDetail.title;
        document.getElementById('ep-detail-podcast').textContent = currentEpisodePodcastTitle;
        document.getElementById('ep-detail-date').textContent = currentEpisodeDetail.pubDateTimestamp > 0
            ? new Date(currentEpisodeDetail.pubDateTimestamp).toLocaleDateString()
            : currentEpisodeDetail.pubDate;
        document.getElementById('ep-detail-description').innerHTML = sanitizeHtml(currentEpisodeDetail.description || '');

        updateEpisodeDetailButtons();
        loadBookmarks(currentEpisodeDetail.id);
    } catch (e) {
        showToast('Erreur: ' + e.message);
    }
}

// ========================
// History
// ========================
async function loadHistory() {
    const list = document.getElementById('history-list');
    const empty = document.getElementById('history-empty');
    const loading = document.getElementById('history-loading');
    const countBadge = document.getElementById('history-count');

    loading.style.display = '';
    list.innerHTML = '';
    empty.style.display = 'none';

    try {
        const res = await fetch('/api/history');
        const history = await res.json();
        loading.style.display = 'none';
        countBadge.textContent = history.length;

        if (history.length === 0) {
            empty.style.display = '';
            return;
        }

        list.innerHTML = history.map(h => {
            const dateStr = new Date(h.listenedAt).toLocaleString();
            const isNowPlaying = playerEpisode && playerEpisode.id === h.episodeId;

            return `
            <div class="history-item ${isNowPlaying ? 'now-playing' : ''}" onclick="openHistoryEpisodeDetail(${h.episodeId}, '${escJs(h.artworkUrl)}', '${escJs(h.podcastTitle)}')">
                <img class="history-item-artwork" src="${esc(h.artworkUrl)}" alt="" onerror="this.src='${placeholderImg()}'">
                <div class="history-item-info">
                    <div class="history-item-title">${esc(h.episodeTitle)}</div>
                    <div class="history-item-podcast">${esc(h.podcastTitle)}</div>
                    <div class="history-item-date">${dateStr}</div>
                </div>
                <div class="history-item-actions">
                    <button class="episode-action-btn" onclick="event.stopPropagation(); playHistoryEpisode(${h.episodeId}, '${escJs(h.artworkUrl)}', '${escJs(h.podcastTitle)}')" title="Lire">
                        <span class="material-icons-round">${isNowPlaying && isPlaying ? 'pause' : 'play_arrow'}</span>
                    </button>
                </div>
            </div>`;
        }).join('');
    } catch (e) {
        loading.style.display = 'none';
        showToast('Erreur: ' + e.message);
    }
}

async function playHistoryEpisode(episodeId, artworkUrl, podcastTitle) {
    if (playerEpisode && playerEpisode.id === episodeId) {
        playerTogglePlay();
        return;
    }
    try {
        await saveCurrentPosition();
        const res = await fetch(`/api/episodes/${episodeId}`);
        const episode = await res.json();

        playerEpisode = episode;
        playerArtwork = artworkUrl || episode.artworkUrl || '';
        playerPodcastTitle = podcastTitle || '';

        const audio = document.getElementById('audio-element');
        audio.src = await resolveAudioUrl(episode);
        audio.playbackRate = playbackSpeed;

        const startPos = episode.playbackPosition > 0 ? episode.playbackPosition / 1000 : 0;
        audio.currentTime = startPos;
        audio.play();

        showPlayerBar();
        startPositionSync();
        updatePlayerUI();
        addToHistory(episodeId);
        // Auto-add to top of playlist
        try { await fetch(`/api/playlist/${episodeId}/top`, { method: 'POST' }); } catch(e) {}
        loadHistory();
    } catch (e) {
        showToast('Erreur lecture: ' + e.message);
    }
}

async function openHistoryEpisodeDetail(episodeId, artworkUrl, podcastTitle) {
    navStack.push('history');
    showPage('episode-detail');
    document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));

    try {
        const res = await fetch(`/api/episodes/${episodeId}`);
        currentEpisodeDetail = await res.json();
        currentEpisodeArtwork = artworkUrl || currentEpisodeDetail.artworkUrl || '';
        currentEpisodePodcastTitle = podcastTitle || '';

        // Try to get podcast detail
        const podcastRes = await fetch(`/api/podcasts/${currentEpisodeDetail.podcastId}`);
        if (podcastRes.ok) {
            currentPodcastDetail = await podcastRes.json();
            currentEpisodePodcastTitle = currentPodcastDetail.title;
        }

        document.getElementById('ep-detail-artwork').src = currentEpisodeArtwork;
        document.getElementById('ep-detail-title').textContent = currentEpisodeDetail.title;
        document.getElementById('ep-detail-podcast').textContent = currentEpisodePodcastTitle;
        document.getElementById('ep-detail-date').textContent = currentEpisodeDetail.pubDateTimestamp > 0
            ? new Date(currentEpisodeDetail.pubDateTimestamp).toLocaleDateString()
            : currentEpisodeDetail.pubDate;
        document.getElementById('ep-detail-description').innerHTML = sanitizeHtml(currentEpisodeDetail.description || '');

        updateEpisodeDetailButtons();
        loadBookmarks(currentEpisodeDetail.id);
    } catch (e) {
        showToast('Erreur: ' + e.message);
    }
}

function clearHistoryConfirm() {
    document.getElementById('dialog-title').textContent = 'Effacer l\'historique';
    document.getElementById('dialog-text').innerHTML = '<p>Voulez-vous vraiment effacer tout l\'historique d\'&eacute;coute ?</p>';
    document.getElementById('dialog-confirm').textContent = 'Effacer';
    document.getElementById('dialog-confirm').className = 'btn-danger';
    document.getElementById('dialog-confirm').onclick = async () => {
        closeDialog();
        try {
            await fetch('/api/history', { method: 'DELETE' });
            showToast('Historique effac\u00e9');
            loadHistory();
        } catch (e) {
            showToast('Erreur: ' + e.message);
        }
    };
    document.getElementById('dialog-overlay').style.display = '';
}

// ========================
// YouTube URL helpers
// ========================
function isYouTubeUrl(url) {
    if (!url) return false;
    return url.includes('youtube.com/watch') || url.includes('youtu.be/');
}

function isYouTubeChannelUrl(query) {
    if (!query) return false;
    return query.match(/youtube\.com\/(channel\/|@|c\/)/i) ||
           query.match(/youtu\.be\//i) && false; // youtu.be is for videos, not channels
}

async function resolveAudioUrl(episode) {
    if (!episode || !episode.audioUrl) return '';
    if (!isYouTubeUrl(episode.audioUrl)) return episode.audioUrl;
    try {
        const res = await authFetch(`/api/youtube/resolve?url=${encodeURIComponent(episode.audioUrl)}`);
        const data = await res.json();
        if (data.audioUrl) return data.audioUrl;
        throw new Error(data.error || 'Failed to resolve YouTube audio');
    } catch (e) {
        showToast('Erreur YouTube: ' + e.message);
        throw e;
    }
}

// ========================
// Audio Player
// ========================
async function playEpisode(episodeId, seekTo) {
    try {
        // Save position of currently playing episode before switching
        await saveCurrentPosition();

        const res = await fetch(`/api/episodes/${episodeId}`);
        const episode = await res.json();

        playerEpisode = episode;
        playerArtwork = episode.artworkUrl || (currentPodcastDetail ? currentPodcastDetail.artworkUrl : '');
        playerPodcastTitle = currentPodcastDetail ? currentPodcastDetail.title : '';

        const audio = document.getElementById('audio-element');
        audio.src = await resolveAudioUrl(episode);
        audio.playbackRate = playbackSpeed;

        // Seek to saved position or specified position
        const startPos = seekTo != null ? seekTo : (episode.playbackPosition > 0 ? episode.playbackPosition / 1000 : 0);
        audio.currentTime = startPos;
        audio.play();

        showPlayerBar();
        startPositionSync();
        updatePlayerUI();
        addToHistory(episodeId);
        // Auto-add to top of playlist
        try { await fetch(`/api/playlist/${episodeId}/top`, { method: 'POST' }); } catch(e) {}
        // Update episode lists if visible
        renderEpisodes();
        updateEpisodeDetailButtons();
    } catch (e) {
        showToast('Erreur lecture: ' + e.message);
    }
}

async function playEpisodeFromPlaylist(episodeId, artworkUrl, podcastTitle) {
    if (playerEpisode && playerEpisode.id === episodeId) {
        playerTogglePlay();
        return;
    }
    try {
        await saveCurrentPosition();

        const res = await fetch(`/api/episodes/${episodeId}`);
        const episode = await res.json();

        playerEpisode = episode;
        playerArtwork = artworkUrl || episode.artworkUrl || '';
        playerPodcastTitle = podcastTitle || '';

        const audio = document.getElementById('audio-element');
        audio.src = await resolveAudioUrl(episode);
        audio.playbackRate = playbackSpeed;

        const startPos = episode.playbackPosition > 0 ? episode.playbackPosition / 1000 : 0;
        audio.currentTime = startPos;
        audio.play();

        showPlayerBar();
        startPositionSync();
        updatePlayerUI();
        addToHistory(episodeId);
        loadPlaylist(); // refresh playlist indicators
    } catch (e) {
        showToast('Erreur lecture: ' + e.message);
    }
}

function playerTogglePlay() {
    const audio = document.getElementById('audio-element');
    if (!playerEpisode) return;

    if (audio.paused) {
        audio.play();
    } else {
        audio.pause();
        saveCurrentPosition();
    }
}

function playerSeek(seconds) {
    const audio = document.getElementById('audio-element');
    if (!playerEpisode) return;
    audio.currentTime = Math.max(0, Math.min(audio.duration || 0, audio.currentTime + seconds));
}

function playerCycleSpeed() {
    const idx = SPEEDS.indexOf(playbackSpeed);
    playbackSpeed = SPEEDS[(idx + 1) % SPEEDS.length];
    const audio = document.getElementById('audio-element');
    audio.playbackRate = playbackSpeed;
    document.getElementById('player-speed-btn').textContent = playbackSpeed + 'x';
}

function setupAudioPipeline() {
    // Only create Web Audio pipeline when volume normalization is actually needed
    if (!volumeNormEnabled) {
        // If pipeline was previously set up, tear it down to restore direct playback
        teardownAudioPipeline();
        return;
    }
    if (audioContext) return; // Already set up

    try {
        const audio = document.getElementById('audio-element');
        // crossOrigin needed for Web Audio API to work with external CDN audio
        audio.crossOrigin = 'anonymous';
        audioContext = new (window.AudioContext || window.webkitAudioContext)();
        sourceNode = audioContext.createMediaElementSource(audio);

        // DynamicsCompressor for volume normalization
        compressorNode = audioContext.createDynamicsCompressor();
        compressorNode.threshold.setValueAtTime(-24, audioContext.currentTime);
        compressorNode.knee.setValueAtTime(30, audioContext.currentTime);
        compressorNode.ratio.setValueAtTime(12, audioContext.currentTime);
        compressorNode.attack.setValueAtTime(0.003, audioContext.currentTime);
        compressorNode.release.setValueAtTime(0.25, audioContext.currentTime);

        // Makeup gain to compensate for compression
        gainNode = audioContext.createGain();
        gainNode.gain.setValueAtTime(1.5, audioContext.currentTime);

        sourceNode.connect(compressorNode);
        compressorNode.connect(gainNode);
        gainNode.connect(audioContext.destination);
    } catch (e) {
        console.error('Failed to setup audio pipeline:', e);
        // Fallback: disable normalization and use direct playback
        teardownAudioPipeline();
        volumeNormEnabled = false;
        localStorage.setItem('volumeNormEnabled', 'false');
        showToast('Normalisation indisponible (CORS)');
    }
}

function teardownAudioPipeline() {
    if (audioContext) {
        try {
            if (sourceNode) sourceNode.disconnect();
            if (compressorNode) compressorNode.disconnect();
            if (gainNode) gainNode.disconnect();
            audioContext.close();
        } catch (e) { /* ignore */ }
        audioContext = null;
        sourceNode = null;
        compressorNode = null;
        gainNode = null;
        // Remove crossOrigin to avoid CORS issues on CDNs that don't support it
        const audio = document.getElementById('audio-element');
        audio.removeAttribute('crossorigin');
    }
}

function toggleVolumeNorm() {
    const wasEnabled = volumeNormEnabled;
    volumeNormEnabled = !volumeNormEnabled;
    localStorage.setItem('volumeNormEnabled', volumeNormEnabled);

    if (volumeNormEnabled) {
        setupAudioPipeline();
    } else {
        // Need to teardown and reload current source (MediaElementSource is permanent)
        const audio = document.getElementById('audio-element');
        const currentTime = audio.currentTime;
        const currentSrc = audio.src;
        const wasPlaying = !audio.paused;

        teardownAudioPipeline();

        // Reload audio to detach from Web Audio API
        if (currentSrc) {
            audio.src = currentSrc;
            audio.currentTime = currentTime;
            if (wasPlaying) audio.play();
        }
    }
    updateVolumeNormButton();
    showToast(volumeNormEnabled ? 'Normalisation du volume activée' : 'Normalisation du volume désactivée');
}

function updateAudioPipeline() {
    if (!audioContext || !sourceNode) return;
    // Disconnect everything first
    sourceNode.disconnect();
    if (compressorNode) compressorNode.disconnect();
    if (gainNode) gainNode.disconnect();

    if (volumeNormEnabled) {
        sourceNode.connect(compressorNode);
        compressorNode.connect(gainNode);
        gainNode.connect(audioContext.destination);
    } else {
        sourceNode.connect(audioContext.destination);
    }
}

function toggleVolumeNorm() {
    volumeNormEnabled = !volumeNormEnabled;
    localStorage.setItem('volumeNormEnabled', volumeNormEnabled);
    updateAudioPipeline();
    updateVolumeNormButton();
    showToast(volumeNormEnabled ? 'Normalisation du volume activ\u00e9e' : 'Normalisation du volume d\u00e9sactiv\u00e9e');
}

function updateVolumeNormButton() {
    const btn = document.getElementById('player-norm-btn');
    if (btn) {
        btn.classList.toggle('active', volumeNormEnabled);
        btn.title = volumeNormEnabled ? 'Normalisation: ON' : 'Normalisation: OFF';
    }
}

function showPlayerBar() {
    document.getElementById('player-bar').style.display = '';
    document.body.classList.add('player-visible');
    // Only set up Web Audio pipeline if volume normalization is enabled
    if (volumeNormEnabled) {
        setupAudioPipeline();
    }
    updateVolumeNormButton();
}

function updatePlayerUI() {
    if (!playerEpisode) return;
    document.getElementById('player-title').textContent = playerEpisode.title;
    document.getElementById('player-subtitle').textContent = playerPodcastTitle;
    document.getElementById('player-artwork').src = playerArtwork;
    document.getElementById('player-artwork').onerror = function() { this.src = placeholderImg(); };
    // YouTube badge on player artwork
    const ytBadge = document.getElementById('player-yt-badge');
    if (ytBadge) {
        ytBadge.style.display = (playerEpisode.sourceType === 'youtube') ? '' : 'none';
    }
    updatePlayButton();
}

function updatePlayButton() {
    const btn = document.getElementById('player-play-btn');
    btn.innerHTML = `<span class="material-icons-round">${isPlaying ? 'pause' : 'play_arrow'}</span>`;
}

function onAudioTimeUpdate() {
    const audio = document.getElementById('audio-element');
    if (!audio.duration || isNaN(audio.duration)) return;

    const pct = (audio.currentTime / audio.duration) * 100;
    document.getElementById('player-progress-fill').style.width = pct + '%';
    document.getElementById('player-progress-input').value = Math.round(pct * 10);
    document.getElementById('player-current-time').textContent = formatTime(audio.currentTime);
    document.getElementById('player-duration').textContent = formatTime(audio.duration);
}

function onAudioLoaded() {
    const audio = document.getElementById('audio-element');
    document.getElementById('player-duration').textContent = formatTime(audio.duration);

    // Seek to saved position
    if (playerEpisode && playerEpisode.playbackPosition > 0 && audio.currentTime < 1) {
        audio.currentTime = playerEpisode.playbackPosition / 1000;
    }
}

async function onAudioEnded() {
    isPlaying = false;
    updatePlayButton();
    stopPositionSync();

    if (!playerEpisode) return;

    // Mark as played
    try {
        await fetch(`/api/episodes/${playerEpisode.id}/played`, { method: 'PUT' });
        // Remove from playlist
        await fetch(`/api/playlist/${playerEpisode.id}`, { method: 'DELETE' });
    } catch (e) {
        console.error('Failed to mark as played', e);
    }

    // Try to play next in playlist
    try {
        const res = await fetch('/api/playlist');
        const items = await res.json();
        if (items.length > 0) {
            const next = items[0];
            playEpisodeFromPlaylist(next.id, next.artworkUrl, next.podcastTitle);
        } else {
            showToast('Playlist termin\u00e9e');
        }
    } catch (e) {
        console.error('Failed to auto-advance', e);
    }
}

function onProgressSeek() {
    const audio = document.getElementById('audio-element');
    if (!audio.duration || isNaN(audio.duration)) return;
    const value = parseInt(document.getElementById('player-progress-input').value);
    audio.currentTime = (value / 1000) * audio.duration;
}

function openPlayerEpisodeDetail() {
    if (!playerEpisode) return;
    // Find the podcast for this episode
    const podcast = allPodcasts.find(p => p.id === playerEpisode.podcastId);
    if (podcast) {
        currentPodcastDetail = podcast;
        currentEpisodePodcastTitle = podcast.title;
    }
    currentEpisodeDetail = playerEpisode;
    currentEpisodeArtwork = playerArtwork;

    navStack.push(currentTab);
    showPage('episode-detail');
    document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));

    document.getElementById('ep-detail-artwork').src = currentEpisodeArtwork;
    document.getElementById('ep-detail-title').textContent = currentEpisodeDetail.title;
    document.getElementById('ep-detail-podcast').textContent = currentEpisodePodcastTitle;
    document.getElementById('ep-detail-date').textContent = currentEpisodeDetail.pubDateTimestamp > 0
        ? new Date(currentEpisodeDetail.pubDateTimestamp).toLocaleDateString()
        : currentEpisodeDetail.pubDate;
    document.getElementById('ep-detail-description').innerHTML = sanitizeHtml(currentEpisodeDetail.description || '');

    updateEpisodeDetailButtons();
    loadBookmarks(currentEpisodeDetail.id);
}

// Position sync — save every 5 seconds while playing
function startPositionSync() {
    stopPositionSync();
    positionSyncInterval = setInterval(async () => {
        if (isPlaying && playerEpisode) {
            await saveCurrentPosition();
        }
    }, 5000);
}

function stopPositionSync() {
    if (positionSyncInterval) {
        clearInterval(positionSyncInterval);
        positionSyncInterval = null;
    }
}

async function addToHistory(episodeId) {
    try {
        await fetch(`/api/history/${episodeId}`, { method: 'POST' });
    } catch (e) {
        console.error('Failed to add to history', e);
    }
}

async function saveCurrentPosition() {
    if (!playerEpisode) return;
    const audio = document.getElementById('audio-element');
    const posMs = Math.round(audio.currentTime * 1000);
    if (posMs <= 0 && !isPlaying) return; // Don't overwrite with 0

    try {
        await fetch(`/api/episodes/${playerEpisode.id}/position`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ position: posMs }),
        });
    } catch (e) {
        console.error('Position sync failed', e);
    }
}

// ========================
// Unsubscribe
// ========================
function confirmUnsubscribe(podcastId, title) {
    document.getElementById('dialog-title').textContent = 'Se d\u00e9sabonner';
    document.getElementById('dialog-text').innerHTML = `<p>Voulez-vous vraiment vous d\u00e9sabonner de "${esc(title)}" ?</p>`;
    document.getElementById('dialog-confirm').textContent = 'Supprimer';
    document.getElementById('dialog-confirm').className = 'btn-danger';
    document.getElementById('dialog-confirm').onclick = async () => {
        closeDialog();
        try {
            await fetch(`/api/podcasts/${podcastId}`, { method: 'DELETE' });
            showToast('D\u00e9sabonn\u00e9 de ' + title);
            loadLibrary();
        } catch (e) {
            showToast('Erreur: ' + e.message);
        }
    };
    document.getElementById('dialog-overlay').style.display = '';
}

function closeDialog() {
    document.getElementById('dialog-overlay').style.display = 'none';
    document.getElementById('dialog-confirm').style.display = '';
    dialogCallback = null;
}

// ========================
// Tag Assignment
// ========================
function showTagAssign(podcastId, title) {
    const podcast = allPodcasts.find(p => p.id === podcastId);
    const podcastTagIds = (podcast?.tags || []).map(t => t.id);

    document.getElementById('dialog-title').textContent = 'Tags \u2014 ' + title;

    let html = '<div style="display:flex;flex-direction:column;gap:8px;margin-bottom:16px;max-height:300px;overflow-y:auto">';
    if (allTags.length === 0) {
        html += '<p style="color:var(--on-surface-variant)">Aucun tag. Cr\u00e9ez-en depuis "G\u00e9rer".</p>';
    } else {
        allTags.forEach(tag => {
            const checked = podcastTagIds.includes(tag.id) ? 'checked' : '';
            html += `<label style="display:flex;align-items:center;gap:8px;cursor:pointer;padding:6px 0">
                <input type="checkbox" ${checked} onchange="toggleTag(${podcastId}, ${tag.id}, this.checked)" style="width:18px;height:18px;accent-color:var(--primary)">
                <span>${esc(tag.name)}</span>
            </label>`;
        });
    }
    html += '</div>';

    document.getElementById('dialog-text').innerHTML = html;
    document.getElementById('dialog-confirm').textContent = 'Fermer';
    document.getElementById('dialog-confirm').className = 'btn-text';
    document.getElementById('dialog-confirm').onclick = () => { closeDialog(); loadLibrary(); };
    document.getElementById('dialog-overlay').style.display = '';
}

async function toggleTag(podcastId, tagId, add) {
    try {
        if (add) {
            await fetch(`/api/podcasts/${podcastId}/tags/${tagId}`, { method: 'POST' });
        } else {
            await fetch(`/api/podcasts/${podcastId}/tags/${tagId}`, { method: 'DELETE' });
        }
    } catch (e) {
        showToast('Erreur: ' + e.message);
    }
}

// ========================
// Tag Manager
// ========================
function openTagManager() {
    document.getElementById('dialog-title').textContent = 'Gérer les tags';

    let html = '<div style="margin-bottom:16px">';
    html += '<div style="display:flex;gap:8px;margin-bottom:12px">';
    html += '<input type="text" id="new-tag-input" placeholder="Nouveau tag..." style="flex:1;padding:8px 12px;border:1px solid var(--outline);border-radius:var(--radius-xs);font-family:inherit;font-size:14px;outline:none">';
    html += '<button onclick="createNewTag()" style="padding:8px 16px;background:var(--primary);color:white;border:none;border-radius:var(--radius-xs);cursor:pointer;font-family:inherit;font-weight:500">Créer</button>';
    html += '</div>';

    // Reorder section
    html += '<div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:8px">';
    html += '<span style="font-size:13px;color:var(--on-surface-variant)">Glissez pour réorganiser</span>';
    html += '</div>';

    html += '<div id="tag-manager-list" class="tag-reorder-list">';
    allTags.forEach(tag => {
        html += `<div class="tag-reorder-item" draggable="true" data-tag-id="${tag.id}">
            <span class="material-icons-round drag-handle">drag_indicator</span>
            <span class="tag-name">${esc(tag.name)}</span>
            <button onclick="deleteTag(${tag.id}, '${escJs(tag.name)}')" style="background:none;border:none;cursor:pointer;color:var(--error);font-size:18px;display:flex;margin-left:auto">
                <span class="material-icons-round" style="font-size:20px">close</span>
            </button>
        </div>`;
    });
    if (allTags.length === 0) {
        html += '<p style="color:var(--on-surface-variant);text-align:center;padding:12px">Aucun tag</p>';
    }
    html += '</div></div>';

    document.getElementById('dialog-text').innerHTML = html;
    document.getElementById('dialog-confirm').textContent = 'Fermer';
    document.getElementById('dialog-confirm').className = 'btn-text';
    document.getElementById('dialog-confirm').onclick = () => { closeDialog(); loadLibrary(); };
    document.getElementById('dialog-overlay').style.display = '';

    // Initialize tag drag-drop reorder
    initTagReorderDragDrop();
}

let draggedTagItem = null;

function initTagReorderDragDrop() {
    const container = document.getElementById('tag-manager-list');
    if (!container) return;
    const items = container.querySelectorAll('.tag-reorder-item[draggable]');

    items.forEach(item => {
        item.addEventListener('dragstart', onTagDragStart);
        item.addEventListener('dragend', onTagDragEnd);
        item.addEventListener('dragover', onTagDragOver);
        item.addEventListener('dragenter', onTagDragEnter);
        item.addEventListener('dragleave', onTagDragLeave);
        item.addEventListener('drop', onTagDrop);
    });
}

function onTagDragStart(e) {
    draggedTagItem = this;
    this.classList.add('dragging');
    e.dataTransfer.effectAllowed = 'move';
    e.dataTransfer.setData('text/plain', this.dataset.tagId);
}

function onTagDragEnd(e) {
    this.classList.remove('dragging');
    document.querySelectorAll('.tag-reorder-item.drag-over').forEach(el => el.classList.remove('drag-over'));
    draggedTagItem = null;
}

function onTagDragOver(e) {
    e.preventDefault();
    e.dataTransfer.dropEffect = 'move';
}

function onTagDragEnter(e) {
    e.preventDefault();
    if (this !== draggedTagItem) {
        this.classList.add('drag-over');
    }
}

function onTagDragLeave(e) {
    this.classList.remove('drag-over');
}

async function onTagDrop(e) {
    e.preventDefault();
    this.classList.remove('drag-over');
    if (!draggedTagItem || this === draggedTagItem) return;

    const container = document.getElementById('tag-manager-list');
    const allItems = [...container.querySelectorAll('.tag-reorder-item[draggable]')];
    const fromIndex = allItems.indexOf(draggedTagItem);
    const toIndex = allItems.indexOf(this);

    // Move the dragged element in the DOM
    if (fromIndex < toIndex) {
        container.insertBefore(draggedTagItem, this.nextSibling);
    } else {
        container.insertBefore(draggedTagItem, this);
    }

    // Collect new order and save
    const newOrder = [...container.querySelectorAll('.tag-reorder-item[draggable]')].map(
        el => parseInt(el.dataset.tagId)
    );
    try {
        await fetch('/api/tags/reorder', {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ tagIds: newOrder }),
        });
        // Refresh allTags with new order
        const tagsRes = await fetch('/api/tags');
        allTags = await tagsRes.json();
        showToast('Ordre des tags mis à jour');
    } catch (err) {
        showToast('Erreur réorganisation: ' + err.message);
    }
}
    html += '</div></div>';

    document.getElementById('dialog-text').innerHTML = html;
    document.getElementById('dialog-confirm').textContent = 'Fermer';
    document.getElementById('dialog-confirm').className = 'btn-text';
    document.getElementById('dialog-confirm').onclick = () => { closeDialog(); loadLibrary(); };
    document.getElementById('dialog-overlay').style.display = '';
}

async function createNewTag() {
    const input = document.getElementById('new-tag-input');
    const name = input.value.trim();
    if (!name) return;
    try {
        const res = await fetch('/api/tags', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ name }),
        });
        if (res.ok) {
            input.value = '';
            const tagsRes = await fetch('/api/tags');
            allTags = await tagsRes.json();
            openTagManager();
            showToast('Tag "' + name + '" cr\u00e9\u00e9');
        }
    } catch (e) {
        showToast('Erreur: ' + e.message);
    }
}

async function deleteTag(tagId, name) {
    try {
        await fetch(`/api/tags/${tagId}`, { method: 'DELETE' });
        const tagsRes = await fetch('/api/tags');
        allTags = await tagsRes.json();
        openTagManager();
        showToast('Tag "' + name + '" supprim\u00e9');
    } catch (e) {
        showToast('Erreur: ' + e.message);
    }
}

// ========================
// Search
// ========================
let searchFromAiSuggestion = false;

async function performSearch(query) {
    const country = document.getElementById('country-select').value;
    const results = document.getElementById('search-results');
    const empty = document.getElementById('search-empty');
    const loading = document.getElementById('search-loading');
    const aiResults = document.getElementById('search-ai-results');
    const aiGrid = document.getElementById('search-ai-grid');
    const aiLoading = document.getElementById('search-ai-loading');
    const itunesHeader = document.getElementById('search-itunes-header');

    // YouTube channel URL detection
    if (query.match(/youtube\.com\/(channel\/|@|c\/)/i)) {
        empty.style.display = 'none';
        aiResults.style.display = 'none';
        itunesHeader.style.display = 'none';
        results.innerHTML = '';
        loading.style.display = '';
        try {
            const res = await authFetch('/api/youtube/subscribe', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ channelUrl: query })
            });
            loading.style.display = 'none';
            const data = await res.json();
            if (data.error) {
                showToast('Erreur YouTube: ' + data.error);
                empty.style.display = '';
                empty.querySelector('p').textContent = data.error;
                return;
            }
            // Show success
            results.innerHTML = `
                <div class="podcast-card">
                    <div class="subscribed-badge"><span class="material-icons-round">check_circle</span> Abonn\u00e9</div>
                    <div class="card-content">
                        <img class="artwork" src="${esc(data.artworkUrl || '')}" alt="${esc(data.name || '')}" onerror="this.src='${placeholderImg()}'">
                        <div class="info">
                            <div class="title"><span class="material-icons-round" style="font-size:16px;vertical-align:middle;color:#f00;margin-right:4px">smart_display</span>${esc(data.name || 'YouTube Channel')}</div>
                            <div class="author">${esc(data.artistName || '')}</div>
                        </div>
                    </div>
                </div>
            `;
            showToast('Abonn\u00e9 \u00e0 ' + (data.name || 'la cha\u00eene YouTube'));
            loadLibrary();
        } catch (e) {
            loading.style.display = 'none';
            showToast('Erreur YouTube: ' + e.message);
        }
        return;
    }

    empty.style.display = 'none';
    loading.style.display = '';
    results.innerHTML = '';

    // Only show AI section if not triggered from an AI suggestion (avoid loops) and AI is enabled
    const aiEnabled = document.getElementById('ai-search-toggle').checked;
    const doAiSearch = !searchFromAiSuggestion && aiEnabled;
    searchFromAiSuggestion = false; // Reset flag

    if (doAiSearch) {
        aiResults.style.display = '';
        aiGrid.innerHTML = '';
        aiLoading.style.display = '';
    } else {
        aiResults.style.display = 'none';
    }
    itunesHeader.style.display = 'none';

    // Build iTunes URL
    let url = `/api/search?q=${encodeURIComponent(query)}`;
    if (country) url += `&country=${country}`;

    // Run iTunes search and AI search in parallel
    const itunesPromise = fetch(url).then(r => r.json());
    const aiPromise = doAiSearch
        ? fetch(`/api/search-ai?q=${encodeURIComponent(query)}`).then(r => r.json()).catch(() => null)
        : Promise.resolve(null);

    try {
        const [data, aiData] = await Promise.all([itunesPromise, aiPromise]);
        loading.style.display = 'none';

        // Render AI suggestions
        if (doAiSearch && aiData && !aiData.error && aiData.suggestions && aiData.suggestions.length > 0) {
            aiLoading.style.display = 'none';
            aiGrid.innerHTML = aiData.suggestions.map(s => `
                <div class="suggestion-card">
                    <div class="suggestion-name">${esc(s.name)}</div>
                    <div class="suggestion-reason">${esc(s.reason)}</div>
                    <button class="btn-search" onclick="searchFromAiSearchSuggestion('${escJs(s.searchQuery)}')">
                        <span class="material-icons-round" style="font-size:18px">search</span>
                        Rechercher
                    </button>
                </div>
            `).join('');
        } else if (doAiSearch) {
            aiLoading.style.display = 'none';
            if (!aiData || aiData.error || !aiData.suggestions || aiData.suggestions.length === 0) {
                aiResults.style.display = 'none';
            }
        }

        // Render iTunes results
        if (data.error) { showToast(data.error); return; }

        if (data.length === 0 && (!doAiSearch || !aiData || !aiData.suggestions || aiData.suggestions.length === 0)) {
            empty.style.display = '';
            empty.querySelector('p').textContent = 'Aucun r\u00e9sultat pour "' + query + '"';
            return;
        }

        if (data.length > 0 && doAiSearch && aiData && aiData.suggestions && aiData.suggestions.length > 0) {
            itunesHeader.style.display = '';
        }

        results.innerHTML = data.map(r => `
            <div class="podcast-card" onclick="openSearchPodcastDetail(${r.collectionId}, '${escJs(r.collectionName)}', '${escJs(r.artistName)}', '${escJs(r.artworkUrl600 || r.artworkUrl100 || '')}', '${escJs(r.feedUrl || '')}', ${r.alreadySubscribed})">
                ${r.alreadySubscribed ? '<div class="subscribed-badge"><span class="material-icons-round">check_circle</span> Abonn\u00e9</div>' : ''}
                <div class="card-content">
                    <img class="artwork" src="${esc(r.artworkUrl600 || r.artworkUrl100 || '')}" alt="${esc(r.collectionName)}" onerror="this.src='${placeholderImg()}'">
                    <div class="info">
                        <div class="title">${esc(r.collectionName)}</div>
                        <div class="author">${esc(r.artistName)}</div>
                    </div>
                </div>
                <div class="card-actions">
                    ${r.alreadySubscribed
                        ? `<button disabled style="opacity:0.5"><span class="material-icons-round" style="font-size:18px">check</span> D\u00e9j\u00e0 abonn\u00e9</button>`
                        : `<button class="subscribe-btn" onclick="event.stopPropagation(); subscribePodcast(${r.collectionId}, '${escJs(r.collectionName)}', '${escJs(r.artistName)}', '${escJs(r.artworkUrl600 || r.artworkUrl100 || '')}', '${escJs(r.feedUrl || '')}', this)">
                            <span class="material-icons-round" style="font-size:18px">add_circle_outline</span>
                            S'abonner
                        </button>`
                    }
                </div>
            </div>
        `).join('');
    } catch (e) {
        loading.style.display = 'none';
        aiLoading.style.display = 'none';
        showToast('Erreur de recherche: ' + e.message);
    }
}

function searchFromAiSearchSuggestion(query) {
    searchFromAiSuggestion = true;
    document.getElementById('search-input').value = query;
    performSearch(query);
}

async function subscribePodcast(collectionId, name, artist, artworkUrl, feedUrl, btn) {
    btn.disabled = true;
    btn.innerHTML = '<div class="spinner" style="width:18px;height:18px;border-width:2px;margin:0"></div> Abonnement...';

    try {
        const res = await fetch('/api/subscribe', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ collectionId, collectionName: name, artistName: artist, artworkUrl, feedUrl }),
        });

        if (res.ok) {
            btn.innerHTML = '<span class="material-icons-round" style="font-size:18px">check</span> Abonn\u00e9';
            btn.style.opacity = '0.5';
            showToast('Abonn\u00e9 \u00e0 ' + name);
            const card = btn.closest('.podcast-card');
            if (card && !card.querySelector('.subscribed-badge')) {
                const badge = document.createElement('div');
                badge.className = 'subscribed-badge';
                badge.innerHTML = '<span class="material-icons-round">check_circle</span> Abonn\u00e9';
                card.prepend(badge);
            }
        } else {
            const err = await res.json();
            throw new Error(err.error || 'Erreur inconnue');
        }
    } catch (e) {
        btn.disabled = false;
        btn.innerHTML = '<span class="material-icons-round" style="font-size:18px">add_circle_outline</span> R\u00e9essayer';
        showToast('Erreur: ' + e.message);
    }
}

// ========================
// AI Discovery
// ========================
async function loadDiscovery() {
    const results = document.getElementById('discover-results');
    const empty = document.getElementById('discover-empty');
    const loading = document.getElementById('discover-loading');
    const intro = document.getElementById('ai-intro');
    const btn = document.getElementById('discover-btn');

    empty.style.display = 'none';
    loading.style.display = '';
    results.innerHTML = '';
    intro.textContent = '';
    intro.style.display = 'none';
    btn.disabled = true;

    try {
        const res = await fetch('/api/discover');
        const data = await res.json();
        loading.style.display = 'none';
        btn.disabled = false;

        if (data.error) { showToast(data.error); empty.style.display = ''; return; }
        if (data.intro) { intro.textContent = data.intro; intro.style.display = ''; }
        if (!data.suggestions || data.suggestions.length === 0) { empty.style.display = ''; return; }

        results.innerHTML = data.suggestions.map(s => `
            <div class="suggestion-card">
                <div class="suggestion-name">${esc(s.name)}</div>
                <div class="suggestion-reason">${esc(s.reason)}</div>
                <button class="btn-search" onclick="searchFromSuggestion('${escJs(s.searchQuery)}')">
                    <span class="material-icons-round" style="font-size:18px">search</span>
                    Rechercher
                </button>
            </div>
        `).join('');
    } catch (e) {
        loading.style.display = 'none';
        btn.disabled = false;
        showToast('Erreur IA: ' + e.message);
    }
}

function searchFromSuggestion(query) {
    searchFromAiSuggestion = true;
    switchTab('search');
    document.getElementById('search-input').value = query;
    performSearch(query);
}

// ========================
// Toast & Dialog
// ========================
function showToast(message) {
    const toast = document.getElementById('toast');
    toast.textContent = message;
    toast.classList.add('show');
    setTimeout(() => toast.classList.remove('show'), 3000);
}

// ========================
// Helpers
// ========================
function esc(str) {
    if (!str) return '';
    const div = document.createElement('div');
    div.textContent = str;
    return div.innerHTML;
}

function escJs(str) {
    if (!str) return '';
    return str.replace(/\\/g, '\\\\').replace(/'/g, "\\'").replace(/"/g, '\\"').replace(/\n/g, '\\n');
}

function sanitizeHtml(html) {
    // Use the browser's DOM parser for safe rendering
    const doc = new DOMParser().parseFromString(html, 'text/html');
    // Remove script tags and event handlers
    doc.querySelectorAll('script, style, iframe, object, embed').forEach(el => el.remove());
    doc.querySelectorAll('*').forEach(el => {
        for (const attr of [...el.attributes]) {
            if (attr.name.startsWith('on') || attr.value.trim().toLowerCase().startsWith('javascript:')) {
                el.removeAttribute(attr.name);
            }
        }
    });
    return doc.body.innerHTML;
}

function formatTime(seconds) {
    if (!seconds || isNaN(seconds)) return '0:00';
    const s = Math.floor(seconds);
    const h = Math.floor(s / 3600);
    const m = Math.floor((s % 3600) / 60);
    const sec = s % 60;
    if (h > 0) {
        return `${h}:${m.toString().padStart(2, '0')}:${sec.toString().padStart(2, '0')}`;
    }
    return `${m}:${sec.toString().padStart(2, '0')}`;
}

function formatDuration(durationSec) {
    if (!durationSec || durationSec <= 0) return '';
    const h = Math.floor(durationSec / 3600);
    const m = Math.floor((durationSec % 3600) / 60);
    if (h > 0) return `${h}h${m > 0 ? m.toString().padStart(2, '0') : ''}`;
    return `${m} min`;
}

function placeholderImg() {
    return "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 80 80'%3E%3Crect fill='%23E8DEF8' width='80' height='80'/%3E%3Ctext x='40' y='48' text-anchor='middle' font-size='30'%3E%F0%9F%8E%99%3C/text%3E%3C/svg%3E";
}
