// Smooth scrolling for navigation links
document.querySelectorAll('a[href^="#"]').forEach(anchor => {
    anchor.addEventListener('click', function (e) {
        // Skip #menu - handled by lightbox
        if (this.getAttribute('href') === '#menu') return;

        e.preventDefault();
        const target = document.querySelector(this.getAttribute('href'));
        if (target) {
            const headerOffset = 80;
            const elementPosition = target.getBoundingClientRect().top;
            const offsetPosition = elementPosition + window.pageYOffset - headerOffset;

            window.scrollTo({
                top: offsetPosition,
                behavior: 'smooth'
            });
        }
    });
});

// Update active navigation link on scroll
window.addEventListener('scroll', () => {
    const sections = document.querySelectorAll('section[id]');
    const navLinks = document.querySelectorAll('.nav-menu a');
    
    let current = '';
    sections.forEach(section => {
        const sectionTop = section.offsetTop;
        const sectionHeight = section.clientHeight;
        if (window.pageYOffset >= sectionTop - 100) {
            current = section.getAttribute('id');
        }
    });

    navLinks.forEach(link => {
        link.classList.remove('active');
        if (link.getAttribute('href') === `#${current}`) {
            link.classList.add('active');
        }
    });
});

// Header background on scroll
const header = document.querySelector('.header');
window.addEventListener('scroll', () => {
    if (window.scrollY > 50) {
        header.style.backgroundColor = 'rgba(45, 45, 45, 0.95)';
        header.style.backdropFilter = 'blur(10px)';
    } else {
        header.style.backgroundColor = 'var(--color-dark-gray)';
        header.style.backdropFilter = 'none';
    }
});

let contentData = null;

function getNestedValue(source, path) {
    return path.split('.').reduce((value, key) => {
        if (value && typeof value === 'object' && key in value) {
            return value[key];
        }
        return undefined;
    }, source);
}

function setElementText(element, value) {
    if (!element || typeof value !== 'string') return;

    element.textContent = value;

    if (element.id === 'reserve-toggle-btn') {
        element.dataset.closedText = value;
    }

    if (element.id === 'reserve-hint') {
        element.dataset.closedText = value;
    }
}

function toggleOptionalElement(element, value) {
    if (!element) return;
    element.hidden = !value;
}

function applySimpleContent(source) {
    document.querySelectorAll('[data-i18n]').forEach(element => {
        const path = element.dataset.i18n;
        const value = getNestedValue(source, path);

        if (typeof value !== 'string') return;

        setElementText(element, value);

        if (element.dataset.i18nOptional === 'true') {
            toggleOptionalElement(element, value.trim());
        }
    });
}

function applyMultiLineContent(source) {
    document.querySelectorAll('[data-i18n-lines]').forEach(element => {
        const basePath = element.dataset.i18nLines;
        const keys = (element.dataset.i18nLineKeys || '')
            .split(',')
            .map(key => key.trim())
            .filter(Boolean);
        const baseValue = getNestedValue(source, basePath);

        if (!baseValue || typeof baseValue !== 'object' || keys.length === 0) return;

        const lines = keys
            .map(key => baseValue[key])
            .filter(value => typeof value === 'string' && value.trim() !== '');

        if (lines.length === 0) return;

        element.innerHTML = lines.map(line => line.replace(/</g, '&lt;').replace(/>/g, '&gt;')).join('<br>');
    });
}

function applyJoinedContent(source) {
    document.querySelectorAll('[data-i18n-join]').forEach(element => {
        const basePath = element.dataset.i18nJoin;
        const keys = (element.dataset.i18nJoinKeys || '')
            .split(',')
            .map(key => key.trim())
            .filter(Boolean);
        const separator = element.dataset.i18nJoinSeparator || ' ';
        const baseValue = getNestedValue(source, basePath);

        if (!baseValue || typeof baseValue !== 'object' || keys.length === 0) return;

        const joinedText = keys
            .map(key => baseValue[key])
            .filter(value => typeof value === 'string' && value.trim() !== '')
            .join(separator);

        if (!joinedText) return;

        element.textContent = joinedText;
    });
}

function applyContent(source) {
    applySimpleContent(source);
    applyMultiLineContent(source);
    applyJoinedContent(source);

    const pageTitle = getNestedValue(source, 'web.nazev');
    if (typeof pageTitle === 'string' && pageTitle.trim()) {
        document.title = pageTitle;
    }
}

async function loadContent() {
    try {
        const response = await fetch('data/content.json');
        if (!response.ok) throw new Error(`HTTP ${response.status}`);

        const data = await response.json();
        contentData = data;
        applyContent(data);
    } catch (error) {
        console.warn('Načítání content.json selhalo, použit fallback z HTML:', error.message);
    }
}

// Menu Lightbox
const menuBtn = document.getElementById('menu-btn');
const menuNavLink = document.querySelector('.nav-menu a[href="#menu"]');
const lightbox = document.getElementById('menu-lightbox');
const lightboxClose = document.querySelector('.lightbox-close');
const menuDocPages = document.getElementById('menu-doc-pages');
const menuDocTabs = Array.from(document.querySelectorAll('.menu-doc-tab'));

const MENU_DOCS = {
    menu: {
        alt: 'Menu S74 wine&café',
        pages: [
            'images/menu-docs/menu-00.jpg'
        ]
    },
    'wine-cz': {
        alt: 'Vinný lístek S74 (CZ)',
        pages: [
            'images/menu-docs/wine-cz-00.jpg',
            'images/menu-docs/wine-cz-01.jpg',
            'images/menu-docs/wine-cz-02.jpg',
            'images/menu-docs/wine-cz-03.jpg'
        ]
    },
    'wine-en': {
        alt: 'Wine list S74 (EN)',
        pages: [
            'images/menu-docs/wine-en-00.jpg',
            'images/menu-docs/wine-en-01.jpg',
            'images/menu-docs/wine-en-02.jpg',
            'images/menu-docs/wine-en-03.jpg'
        ]
    }
};

function renderMenuDocument(docKey) {
    if (!menuDocPages || !MENU_DOCS[docKey]) return;

    const doc = MENU_DOCS[docKey];
    menuDocPages.innerHTML = '';

    doc.pages.forEach((src, index) => {
        const img = document.createElement('img');
        img.className = 'menu-doc-image';
        img.src = src;
        img.loading = index === 0 ? 'eager' : 'lazy';
        img.alt = `${doc.alt} - stránka ${index + 1}`;
        menuDocPages.appendChild(img);
    });

    menuDocTabs.forEach(tab => {
        const isActive = tab.dataset.doc === docKey;
        tab.classList.toggle('active', isActive);
        tab.setAttribute('aria-selected', isActive ? 'true' : 'false');
    });
}

function openLightbox(e) {
    e.preventDefault();
    const activeTab = menuDocTabs.find(tab => tab.classList.contains('active'));
    if (activeTab) {
        renderMenuDocument(activeTab.dataset.doc);
    }
    lightbox.classList.add('active');
    document.body.style.overflow = 'hidden';
}

function closeLightbox() {
    lightbox.classList.remove('active');
    document.body.style.overflow = '';
}

if (menuBtn) {
    menuBtn.addEventListener('click', openLightbox);
}

if (menuNavLink) {
    menuNavLink.addEventListener('click', openLightbox);
}

if (lightboxClose) {
    lightboxClose.addEventListener('click', closeLightbox);
}

if (lightbox) {
    lightbox.addEventListener('click', (e) => {
        if (e.target === lightbox) {
            closeLightbox();
        }
    });
}

menuDocTabs.forEach(tab => {
    tab.addEventListener('click', () => {
        renderMenuDocument(tab.dataset.doc);
    });
});

const initialMenuTab = menuDocTabs.find(tab => tab.classList.contains('active'));
if (initialMenuTab) {
    renderMenuDocument(initialMenuTab.dataset.doc);
}

document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && lightbox && lightbox.classList.contains('active')) {
        closeLightbox();
    }
});

// ==========================================
// Wine Section
// ==========================================

// Fallback data pro případ, že fetch selže (file:// protokol)
const WINES_FALLBACK = [
    {
      "id": "gruner-veltliner-federspiel-2024",
      "name": "Grüner Veltliner Federspiel Terrassen",
      "year": 2024,
      "type": "white",
      "typeLabel": "Bílé",
      "taste": "Suché",
      "country": "Rakousko",
      "producer": "Domäne Wachau",
      "description": "Vůně bílého pepře, jemné tóny bylin, tropického ovoce, s dotykem zralého žlutého jablka. Středně plná chuť se svěží kyselinou. Harmonické a šťavnaté víno s pikantním závěrem. Velmi typický zástupce kategorie Federspiel.",
      "alcohol": "12.5%",
      "prices": {
        "glass": 60,
        "bottle": 399
      },
      "image": "images/vino1.jpeg"
    }
];

let winesData = [];
let activeFilters = {
    type: 'all',
    country: 'all'
};

// Fetch wines data
async function loadWines() {
    try {
        const response = await fetch('data/wines.json');
        if (!response.ok) throw new Error('Fetch failed');
        const data = await response.json();
        winesData = data.wines;
    } catch (error) {
        console.warn('Načítání z JSON selhalo, použit fallback:', error.message);
        winesData = WINES_FALLBACK;
    }

    generateCountryFilters();
    renderWines();
}

// Generate country filter buttons dynamically
function generateCountryFilters() {
    const countries = [...new Set(winesData.map(wine => wine.country))];
    const countryFilterGroup = document.querySelectorAll('.filter-group')[1];

    if (!countryFilterGroup) return;

    countries.forEach(country => {
        const btn = document.createElement('button');
        btn.className = 'filter-btn';
        btn.dataset.filter = 'country';
        btn.dataset.value = country;
        btn.textContent = country;
        countryFilterGroup.appendChild(btn);
    });

    // Add click listeners to all filter buttons
    document.querySelectorAll('.filter-btn').forEach(btn => {
        btn.addEventListener('click', handleFilterClick);
    });
}

// Handle filter button click
function handleFilterClick(e) {
    const btn = e.target;
    const filterType = btn.dataset.filter;
    const filterValue = btn.dataset.value;

    // Update active state in UI
    const filterGroup = btn.closest('.filter-group');
    filterGroup.querySelectorAll('.filter-btn').forEach(b => b.classList.remove('active'));
    btn.classList.add('active');

    // Update active filters
    activeFilters[filterType] = filterValue;

    // Re-render wines
    renderWines();
}

// Render wine cards
function renderWines() {
    const grid = document.getElementById('wine-grid');
    const emptyMsg = document.getElementById('wine-empty');

    if (!grid) return;

    // Filter wines
    const filteredWines = winesData.filter(wine => {
        const typeMatch = activeFilters.type === 'all' || wine.type === activeFilters.type;
        const countryMatch = activeFilters.country === 'all' || wine.country === activeFilters.country;
        return typeMatch && countryMatch;
    });

    // Clear grid
    grid.innerHTML = '';

    if (filteredWines.length === 0) {
        emptyMsg.style.display = 'block';
        return;
    }

    emptyMsg.style.display = 'none';

    // Create wine cards
    filteredWines.forEach(wine => {
        const card = createWineCard(wine);
        grid.appendChild(card);
    });
}

// Create a wine card element
function createWineCard(wine) {
    const card = document.createElement('article');
    card.className = 'wine-card';
    card.dataset.wineId = wine.id;

    card.innerHTML = `
        <div class="wine-card-image">
            <img src="${wine.image}" alt="${wine.name}" loading="lazy">
        </div>
        <div class="wine-card-content">
            <span class="wine-card-type">${wine.typeLabel}</span>
            <h3 class="wine-card-title">${wine.name}</h3>
            <p class="wine-card-year">${wine.year}</p>
            <div class="wine-card-footer">
                <span class="wine-card-country">${wine.country}</span>
                <span class="wine-card-price">${wine.prices.bottle},- <span>Kč</span></span>
            </div>
        </div>
    `;

    card.addEventListener('click', () => openWineDetail(wine));

    return card;
}

// Wine Detail Lightbox
const wineLightbox = document.getElementById('wine-lightbox');
const wineLightboxClose = document.querySelector('.wine-lightbox-close');

function openWineDetail(wine) {
    // Populate detail content
    document.getElementById('wine-detail-img').src = wine.image;
    document.getElementById('wine-detail-img').alt = wine.name;
    document.getElementById('wine-detail-type').textContent = `${wine.typeLabel} • ${wine.taste}`;
    document.getElementById('wine-detail-title').textContent = `${wine.name} ${wine.year}`;
    document.getElementById('wine-detail-description').textContent = wine.description;
    document.getElementById('wine-detail-producer').textContent = wine.producer;
    document.getElementById('wine-detail-country').textContent = wine.country;
    document.getElementById('wine-detail-year').textContent = wine.year;
    document.getElementById('wine-detail-alcohol').textContent = wine.alcohol;
    document.getElementById('wine-detail-taste').textContent = wine.taste;
    document.getElementById('wine-detail-price-glass').textContent = `${wine.prices.glass},-`;
    document.getElementById('wine-detail-price-bottle').textContent = `${wine.prices.bottle},-`;

    // Open lightbox
    wineLightbox.classList.add('active');
    document.body.style.overflow = 'hidden';
}

function closeWineLightbox() {
    wineLightbox.classList.remove('active');
    document.body.style.overflow = '';
}

if (wineLightboxClose) {
    wineLightboxClose.addEventListener('click', closeWineLightbox);
}

if (wineLightbox) {
    wineLightbox.addEventListener('click', (e) => {
        if (e.target === wineLightbox) {
            closeWineLightbox();
        }
    });
}

// Close wine lightbox on Escape
document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && wineLightbox && wineLightbox.classList.contains('active')) {
        closeWineLightbox();
    }
});

// Initialize wines on page load
document.addEventListener('DOMContentLoaded', loadWines);

// ==========================================
// Reservations (Tebi widget)
// ==========================================

function initTebiReservations() {
    const allowedPlacements = new Set(['floating', 'events', 'header', 'button']);
    const urlParams = new URLSearchParams(window.location.search);
    let placement = urlParams.get('tebi') || 'events';
    if (!allowedPlacements.has(placement)) placement = 'events';

    // On small screens, the header slot is hidden; fall back to the button-in-events flow.
    if (placement === 'header' && window.matchMedia && window.matchMedia('(max-width: 768px)').matches) {
        placement = 'button';
    }

    document.body.dataset.tebiPlacement = placement;

    const headerSlot = document.getElementById('reserve-slot-header');
    const eventsSlot = document.getElementById('reserve-slot-events');
    const eventsLayout = document.querySelector('.events-layout');
    const panel = document.getElementById('reserve-panel');
    const toggleBtn = document.getElementById('reserve-toggle-btn');
    const eventsHint = document.getElementById('reserve-hint');
    const closedButtonText = () => toggleBtn?.dataset.closedText || 'Rezervovat stůl';
    const closedHintText = () => eventsHint?.dataset.closedText || 'Kliknutím zobrazíte rezervační widget.';

    // UI toggles per mode.
    if (panel) {
        // Only visible by default in "events" mode; "button" controls it, "header"/"floating" keep it hidden.
        panel.hidden = placement !== 'events';
    }

    if (toggleBtn) {
        toggleBtn.style.display = placement === 'button' ? '' : 'none';
        toggleBtn.setAttribute('aria-expanded', 'false');
    }

    if (eventsHint) {
        eventsHint.style.display = placement === 'button' ? '' : 'none';
    }

    // For managed placements we hide the injected floating iframe until we mount it.
    if (placement !== 'floating') {
        document.body.classList.add('tebi-managed');
    }

    function getTebiIframe() {
        return (
            document.querySelector('iframe[id^="tebi_rs_"]') ||
            document.querySelector('iframe[src*="live.tebi.co/api/widget/" i]')
        );
    }

    function chooseSlot() {
        if (placement === 'header' && headerSlot) return headerSlot;
        if ((placement === 'events' || placement === 'button') && eventsSlot) return eventsSlot;
        return null;
    }

    function normalizeIframe(iframe) {
        // Convert the injected fixed-position launcher into an inline element.
        iframe.style.position = 'static';
        iframe.style.inset = 'auto';
        iframe.style.right = 'auto';
        iframe.style.bottom = 'auto';
        iframe.style.zIndex = 'auto';
        iframe.style.transform = 'none';
    }

    let mounted = false;
    function mountIframeIfReady() {
        const iframe = getTebiIframe();
        if (!iframe || mounted) return Boolean(iframe);

        const slot = chooseSlot();
        if (!slot) {
            // No suitable slot; just let it behave as the default floating launcher.
            document.body.classList.remove('tebi-managed');
            document.body.classList.add('tebi-mounted');
            mounted = true;
            return true;
        }

        normalizeIframe(iframe);
        slot.appendChild(iframe);
        mounted = true;

        if (placement !== 'button') {
            document.body.classList.add('tebi-mounted');
        }

        return true;
    }

    // If already injected, mount immediately; otherwise observe.
    if (!mountIframeIfReady()) {
        const observer = new MutationObserver(() => {
            if (mountIframeIfReady()) observer.disconnect();
        });
        observer.observe(document.documentElement, { childList: true, subtree: true });
    }

    function setToggleUi(open) {
        if (!toggleBtn) return;
        toggleBtn.setAttribute('aria-expanded', open ? 'true' : 'false');
        toggleBtn.textContent = open ? 'Skrýt rezervace' : closedButtonText();
        if (eventsHint) {
            eventsHint.textContent = open
                ? 'Kliknutím widget skryjete.'
                : closedHintText();
        }
    }

    function setOpen(open) {
        if (panel) panel.hidden = !open;

        // Fallback pro browsery bez :has() - nastavit data attribute
        const eventsRight = document.querySelector('.events-right');
        if (eventsRight) {
            eventsRight.dataset.hidden = !open;
        }

        if (open) {
            mountIframeIfReady();
            document.body.classList.add('tebi-mounted');
            // Žádný scroll - uživatel už je v Events sekci, kde kliknul
        } else {
            document.body.classList.remove('tebi-mounted');
        }
        setToggleUi(open);
    }

    if (placement === 'events') {
        // Always visible in "events" mode.
        setOpen(true);
    }

    if (placement === 'button' && toggleBtn) {
        let open = false;
        setOpen(open);

        toggleBtn.addEventListener('click', () => {
            open = !open;
            setOpen(open);
        });
    }
}

// Shared gallery lightbox
(function () {
    const galleries = {
        'dezerty-card': {
            photos: [
                'images/deserty/desserts.jpg',
                'images/deserty/jidlo-prkenko.jpg',
                'images/deserty/limo-1.jpg',
                'images/deserty/s-malinou.png'
            ],
            alts: ['Dezerty', 'Prkenko s občerstvením', 'Limonáda', 'Dezert s malinou']
        },
        'kava-card': {
            photos: [
                'images/kava/latte-art.jpg',
                'images/kava/kava-3-druhy.jpg',
                'images/kava/kava-costarica.webp',
                'images/kava/kava-guatemala.webp',
                'images/kava/kava-mexiko.webp'
            ],
            alts: ['Latte art', '3 druhy kávy', 'Costa Rica', 'Guatemala', 'Mexiko']
        },
        'vina-card': {
            photos: [
                'images/vina/vino-luxus.png',
                'images/vina/vine.jpg',
                'images/vina/vino-korky.png'
            ],
            alts: ['Vybraná vína', 'Nalévání vína', 'Víno a korky']
        },
        'atmosfera-card': {
            photos: [
                'images/atmosfera/interier-shora.jpg',
                'images/atmosfera/interier-sloup.jpg',
                'images/atmosfera/open-tabule.png',
                'images/atmosfera/pohled-z-ulice.jpg',
                'images/atmosfera/posezeni-obrazy.jpg',
                'images/atmosfera/s74-vitrina.png',
                'images/atmosfera/vino-knihy.png'
            ],
            alts: [
                'Atmosféra interiéru',
                'Interiér se sloupem',
                'Otevřená tabule',
                'Pohled z ulice',
                'Posezení s obrazy',
                'Výloha S74',
                'Víno a knihy'
            ]
        }
    };

    let activeGallery = null;
    let currentIndex = 0;
    const lightbox = document.getElementById('gallery-lightbox');
    const title = document.getElementById('gallery-title');
    const slideImg = document.getElementById('gallery-slide-img');
    const dotsContainer = document.getElementById('gallery-dots');

    if (!lightbox || !title || !slideImg || !dotsContainer) {
        return;
    }

    function updateDots() {
        dotsContainer.querySelectorAll('.gallery-dot').forEach((dot, i) => {
            dot.classList.toggle('active', i === currentIndex);
        });
    }

    function renderDots() {
        if (!activeGallery) return;

        dotsContainer.innerHTML = '';
        activeGallery.photos.forEach((_, i) => {
            const dot = document.createElement('button');
            dot.type = 'button';
            dot.className = 'gallery-dot' + (i === currentIndex ? ' active' : '');
            dot.setAttribute('aria-label', `Fotka ${i + 1}`);
            dot.addEventListener('click', () => openGallery(activeGallery.cardId, i));
            dotsContainer.appendChild(dot);
        });
    }

    function openGallery(cardId, index) {
        const gallery = galleries[cardId];
        const card = document.getElementById(cardId);
        if (!gallery || !card) return;

        activeGallery = { ...gallery, cardId };
        currentIndex = index;
        title.textContent = card.querySelector('.card-title')?.textContent?.trim() || '';
        slideImg.src = activeGallery.photos[currentIndex];
        slideImg.alt = activeGallery.alts[currentIndex] || title.textContent;
        renderDots();
        updateDots();
        lightbox.classList.add('active');
        document.body.style.overflow = 'hidden';
    }

    function closeGallery() {
        lightbox.classList.remove('active');
        document.body.style.overflow = '';
    }

    Object.keys(galleries).forEach((cardId) => {
        const card = document.getElementById(cardId);
        if (!card) return;

        card.addEventListener('click', () => openGallery(cardId, 0));
        card.addEventListener('keydown', (e) => {
            if (e.key === 'Enter' || e.key === ' ') {
                e.preventDefault();
                openGallery(cardId, 0);
            }
        });
    });

    lightbox.querySelector('.gallery-arrow-prev').addEventListener('click', () => {
        if (!activeGallery) return;
        openGallery(
            activeGallery.cardId,
            (currentIndex - 1 + activeGallery.photos.length) % activeGallery.photos.length
        );
    });
    lightbox.querySelector('.gallery-arrow-next').addEventListener('click', () => {
        if (!activeGallery) return;
        openGallery(activeGallery.cardId, (currentIndex + 1) % activeGallery.photos.length);
    });

    lightbox.querySelector('.lightbox-close').addEventListener('click', closeGallery);

    lightbox.addEventListener('click', (e) => {
        if (!e.target.closest('.gallery-slideshow')) closeGallery();
    });

    document.addEventListener('keydown', (e) => {
        if (!lightbox.classList.contains('active')) return;
        if (!activeGallery) return;

        if (e.key === 'Escape') closeGallery();
        if (e.key === 'ArrowLeft') {
            openGallery(
                activeGallery.cardId,
                (currentIndex - 1 + activeGallery.photos.length) % activeGallery.photos.length
            );
        }
        if (e.key === 'ArrowRight') {
            openGallery(activeGallery.cardId, (currentIndex + 1) % activeGallery.photos.length);
        }
    });
}());

document.addEventListener('DOMContentLoaded', () => {
    const contentReady = loadContent();
    loadWines();
    contentReady.finally(() => {
        initTebiReservations();
    });
});
