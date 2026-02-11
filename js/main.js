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
