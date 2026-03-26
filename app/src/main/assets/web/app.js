// Podcasto Web Management UI

// ========================
// State
// ========================
let currentTab = 'library';
let allTags = [];
let selectedTagId = null;
let allPodcasts = [];
let searchDebounceTimer = null;
let dialogCallback = null;

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
                document.getElementById('search-empty').style.display = '';
                document.getElementById('search-loading').style.display = 'none';
            }
        }, 400);
    });

    // Country select triggers re-search
    document.getElementById('country-select').addEventListener('change', () => {
        const query = searchInput.value.trim();
        if (query.length >= 2) {
            performSearch(query);
        }
    });

    // Load library on start
    loadLibrary();
});

// ========================
// Tabs
// ========================
function switchTab(tabName) {
    currentTab = tabName;
    document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
    document.querySelectorAll('.page').forEach(p => p.classList.remove('active'));
    document.querySelector(`.tab[data-tab="${tabName}"]`).classList.add('active');
    document.getElementById(tabName).classList.add('active');

    if (tabName === 'library') {
        loadLibrary();
    }
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
    if (allTags.length === 0) {
        container.innerHTML = '';
        return;
    }

    let html = `<button class="tag-chip ${selectedTagId === null ? 'active' : ''}" onclick="filterByTag(null)">Tous</button>`;
    allTags.forEach(tag => {
        html += `<button class="tag-chip ${selectedTagId === tag.id ? 'active' : ''}" onclick="filterByTag(${tag.id})">${escapeHtml(tag.name)}</button>`;
    });

    // Add a "manage tags" chip
    html += `<button class="tag-chip" onclick="openTagManager()" style="border-style:dashed">+ Gérer</button>`;
    container.innerHTML = html;
}

function filterByTag(tagId) {
    selectedTagId = tagId;
    renderTagsFilter();

    if (tagId === null) {
        renderLibrary(allPodcasts);
    } else {
        const filtered = allPodcasts.filter(p => p.tags && p.tags.some(t => t.id === tagId));
        renderLibrary(filtered);
    }
}

function renderLibrary(podcasts) {
    const grid = document.getElementById('podcasts-grid');
    const empty = document.getElementById('library-empty');
    const count = document.getElementById('podcast-count');

    count.textContent = podcasts.length;

    if (podcasts.length === 0) {
        grid.innerHTML = '';
        empty.style.display = '';
        return;
    }

    empty.style.display = 'none';
    grid.innerHTML = podcasts.map(p => `
        <div class="podcast-card">
            <div class="card-content">
                <img class="artwork" src="${escapeHtml(p.artworkUrl)}" alt="${escapeHtml(p.title)}" onerror="this.src='data:image/svg+xml,<svg xmlns=%22http://www.w3.org/2000/svg%22 viewBox=%220 0 80 80%22><rect fill=%22%23E8DEF8%22 width=%2280%22 height=%2280%22/><text x=%2240%22 y=%2245%22 text-anchor=%22middle%22 font-size=%2230%22>🎙</text></svg>'">
                <div class="info">
                    <div class="title">${escapeHtml(p.title)}</div>
                    <div class="author">${escapeHtml(p.author)}</div>
                    <div class="card-tags">
                        ${(p.tags || []).map(t => `<span class="card-tag">${escapeHtml(t.name)}</span>`).join('')}
                    </div>
                </div>
            </div>
            <div class="card-actions">
                <button onclick="showTagAssign(${p.id}, '${escapeJs(p.title)}')">
                    <span class="material-icons-round" style="font-size:18px">label</span>
                    Tags
                </button>
                <button class="danger" onclick="confirmUnsubscribe(${p.id}, '${escapeJs(p.title)}')">
                    <span class="material-icons-round" style="font-size:18px">delete_outline</span>
                    Supprimer
                </button>
            </div>
        </div>
    `).join('');
}

// ========================
// Unsubscribe
// ========================
function confirmUnsubscribe(podcastId, title) {
    document.getElementById('dialog-title').textContent = 'Se désabonner';
    document.getElementById('dialog-text').textContent = `Voulez-vous vraiment vous désabonner de "${title}" ?`;
    document.getElementById('dialog-confirm').textContent = 'Supprimer';
    document.getElementById('dialog-confirm').className = 'btn-danger';
    document.getElementById('dialog-overlay').style.display = '';

    dialogCallback = async () => {
        closeDialog();
        try {
            await fetch(`/api/podcasts/${podcastId}`, { method: 'DELETE' });
            showToast('Désabonné de ' + title);
            loadLibrary();
        } catch (e) {
            showToast('Erreur: ' + e.message);
        }
    };
    document.getElementById('dialog-confirm').onclick = dialogCallback;
}

function closeDialog() {
    document.getElementById('dialog-overlay').style.display = 'none';
    dialogCallback = null;
}

// ========================
// Tag Assignment
// ========================
function showTagAssign(podcastId, title) {
    // Build a dialog showing all tags with checkboxes
    const dialog = document.querySelector('.dialog');
    document.getElementById('dialog-title').textContent = 'Tags — ' + title;

    const podcast = allPodcasts.find(p => p.id === podcastId);
    const podcastTagIds = (podcast?.tags || []).map(t => t.id);

    let html = '<div style="display:flex;flex-direction:column;gap:8px;margin-bottom:16px;max-height:300px;overflow-y:auto">';
    if (allTags.length === 0) {
        html += '<p style="color:var(--on-surface-variant)">Aucun tag. Créez-en depuis le bouton "Gérer" dans la barre de tags.</p>';
    } else {
        allTags.forEach(tag => {
            const checked = podcastTagIds.includes(tag.id) ? 'checked' : '';
            html += `<label style="display:flex;align-items:center;gap:8px;cursor:pointer;padding:6px 0">
                <input type="checkbox" ${checked} onchange="toggleTag(${podcastId}, ${tag.id}, this.checked)" style="width:18px;height:18px;accent-color:var(--primary)">
                <span>${escapeHtml(tag.name)}</span>
            </label>`;
        });
    }
    html += '</div>';

    document.getElementById('dialog-text').innerHTML = html;
    document.getElementById('dialog-confirm').textContent = 'Fermer';
    document.getElementById('dialog-confirm').className = 'btn-text';
    document.getElementById('dialog-confirm').onclick = () => {
        closeDialog();
        loadLibrary(); // Refresh to show updated tags
    };
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
    html += '<div id="tag-manager-list" style="display:flex;flex-direction:column;gap:4px;max-height:250px;overflow-y:auto">';
    allTags.forEach(tag => {
        html += `<div style="display:flex;align-items:center;justify-content:space-between;padding:8px 12px;background:var(--surface-variant);border-radius:var(--radius-xs)">
            <span>${escapeHtml(tag.name)}</span>
            <button onclick="deleteTag(${tag.id}, '${escapeJs(tag.name)}')" style="background:none;border:none;cursor:pointer;color:var(--error);font-size:18px;display:flex">
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
    document.getElementById('dialog-confirm').onclick = () => {
        closeDialog();
        loadLibrary();
    };
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
            // Reload tags and refresh manager
            const tagsRes = await fetch('/api/tags');
            allTags = await tagsRes.json();
            openTagManager(); // Re-render the manager dialog
            showToast('Tag "' + name + '" créé');
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
        openTagManager(); // Re-render
        showToast('Tag "' + name + '" supprimé');
    } catch (e) {
        showToast('Erreur: ' + e.message);
    }
}

// ========================
// Search
// ========================
async function performSearch(query) {
    const country = document.getElementById('country-select').value;
    const results = document.getElementById('search-results');
    const empty = document.getElementById('search-empty');
    const loading = document.getElementById('search-loading');

    empty.style.display = 'none';
    loading.style.display = '';
    results.innerHTML = '';

    try {
        let url = `/api/search?q=${encodeURIComponent(query)}`;
        if (country) url += `&country=${country}`;

        const res = await fetch(url);
        const data = await res.json();

        loading.style.display = 'none';

        if (data.error) {
            showToast(data.error);
            return;
        }

        if (data.length === 0) {
            empty.style.display = '';
            empty.querySelector('p').textContent = 'Aucun résultat pour "' + query + '"';
            return;
        }

        results.innerHTML = data.map(r => `
            <div class="podcast-card">
                ${r.alreadySubscribed ? '<div class="subscribed-badge"><span class="material-icons-round">check_circle</span> Abonné</div>' : ''}
                <div class="card-content">
                    <img class="artwork" src="${escapeHtml(r.artworkUrl600 || r.artworkUrl100 || '')}" alt="${escapeHtml(r.collectionName)}" onerror="this.src='data:image/svg+xml,<svg xmlns=%22http://www.w3.org/2000/svg%22 viewBox=%220 0 80 80%22><rect fill=%22%23E8DEF8%22 width=%2280%22 height=%2280%22/><text x=%2240%22 y=%2245%22 text-anchor=%22middle%22 font-size=%2230%22>🎙</text></svg>'">
                    <div class="info">
                        <div class="title">${escapeHtml(r.collectionName)}</div>
                        <div class="author">${escapeHtml(r.artistName)}</div>
                    </div>
                </div>
                <div class="card-actions">
                    ${r.alreadySubscribed
                        ? `<button disabled style="opacity:0.5"><span class="material-icons-round" style="font-size:18px">check</span> Déjà abonné</button>`
                        : `<button class="subscribe-btn" onclick="subscribePodcast(${r.collectionId}, '${escapeJs(r.collectionName)}', '${escapeJs(r.artistName)}', '${escapeJs(r.artworkUrl600 || r.artworkUrl100 || '')}', '${escapeJs(r.feedUrl || '')}', this)">
                            <span class="material-icons-round" style="font-size:18px">add_circle_outline</span>
                            S'abonner
                        </button>`
                    }
                </div>
            </div>
        `).join('');
    } catch (e) {
        loading.style.display = 'none';
        showToast('Erreur de recherche: ' + e.message);
    }
}

async function subscribePodcast(collectionId, name, artist, artworkUrl, feedUrl, btn) {
    btn.disabled = true;
    btn.innerHTML = '<div class="spinner" style="width:18px;height:18px;border-width:2px;margin:0"></div> Abonnement...';

    try {
        const res = await fetch('/api/subscribe', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                collectionId,
                collectionName: name,
                artistName: artist,
                artworkUrl,
                feedUrl,
            }),
        });

        if (res.ok) {
            btn.innerHTML = '<span class="material-icons-round" style="font-size:18px">check</span> Abonné';
            btn.style.opacity = '0.5';
            showToast('Abonné à ' + name);

            // Add subscribed badge to the card
            const card = btn.closest('.podcast-card');
            if (card && !card.querySelector('.subscribed-badge')) {
                const badge = document.createElement('div');
                badge.className = 'subscribed-badge';
                badge.innerHTML = '<span class="material-icons-round">check_circle</span> Abonné';
                card.prepend(badge);
            }
        } else {
            const err = await res.json();
            throw new Error(err.error || 'Erreur inconnue');
        }
    } catch (e) {
        btn.disabled = false;
        btn.innerHTML = '<span class="material-icons-round" style="font-size:18px">add_circle_outline</span> Réessayer';
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

        if (data.error) {
            showToast(data.error);
            empty.style.display = '';
            return;
        }

        if (data.intro) {
            intro.textContent = data.intro;
            intro.style.display = '';
        }

        if (!data.suggestions || data.suggestions.length === 0) {
            empty.style.display = '';
            return;
        }

        results.innerHTML = data.suggestions.map(s => `
            <div class="suggestion-card">
                <div class="suggestion-name">${escapeHtml(s.name)}</div>
                <div class="suggestion-reason">${escapeHtml(s.reason)}</div>
                <button class="btn-search" onclick="searchFromSuggestion('${escapeJs(s.searchQuery)}')">
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
    switchTab('search');
    document.getElementById('search-input').value = query;
    performSearch(query);
}

// ========================
// Toast
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
function escapeHtml(str) {
    if (!str) return '';
    const div = document.createElement('div');
    div.textContent = str;
    return div.innerHTML;
}

function escapeJs(str) {
    if (!str) return '';
    return str.replace(/\\/g, '\\\\').replace(/'/g, "\\'").replace(/"/g, '\\"').replace(/\n/g, '\\n');
}
