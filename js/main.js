const header = document.querySelector('.header');
const navToggle = document.querySelector('.nav-toggle');
const navPanel = document.getElementById('primary-navigation');
const mobileNavigation = window.matchMedia('(max-width: 56rem)');
const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)');
const focusableSelector = [
    'a[href]',
    'button:not([disabled])',
    'input:not([disabled])',
    'select:not([disabled])',
    'textarea:not([disabled])',
    '[tabindex]:not([tabindex="-1"])'
].join(',');
let activeModal = null;
let backgroundState = new Map();
let bodyChildObserver = null;

function makeBackgroundInert(element) {
    if (!(element instanceof HTMLElement) || element === activeModal || element.tagName === 'SCRIPT') return;
    if (!backgroundState.has(element)) {
        backgroundState.set(element, {
            inert: element.inert,
            ariaHidden: element.getAttribute('aria-hidden')
        });
    }
    element.inert = true;
    element.setAttribute('aria-hidden', 'true');
}

function activateModal(modal, initialFocus) {
    if (!modal) return;

    activeModal = modal;
    modal.inert = false;
    modal.setAttribute('aria-hidden', 'false');
    modal.focus({ preventScroll: true });
    backgroundState = new Map();
    Array.from(document.body.children).forEach(makeBackgroundInert);
    bodyChildObserver = new MutationObserver(mutations => {
        mutations.forEach(mutation => {
            mutation.addedNodes.forEach(node => makeBackgroundInert(node));
        });
    });
    bodyChildObserver.observe(document.body, { childList: true });

    document.body.classList.add('modal-open');
    document.body.style.overflow = 'hidden';
    window.requestAnimationFrame(() => (initialFocus || modal).focus());
}

function deactivateModal(modal, returnFocusTo) {
    if (!modal) return;

    bodyChildObserver?.disconnect();
    bodyChildObserver = null;
    backgroundState.forEach((state, element) => {
        element.inert = state.inert;
        if (state.ariaHidden === null) {
            element.removeAttribute('aria-hidden');
        } else {
            element.setAttribute('aria-hidden', state.ariaHidden);
        }
    });
    backgroundState.clear();

    document.body.classList.remove('modal-open');
    document.body.style.overflow = '';
    activeModal = null;
    returnFocusTo?.focus();
    modal.setAttribute('aria-hidden', 'true');
}

function trapModalFocus(event) {
    if (event.key !== 'Tab' || !activeModal) return;

    const focusable = Array.from(activeModal.querySelectorAll(focusableSelector))
        .filter(element => element.tabIndex >= 0 && !element.inert && element.getClientRects().length > 0);
    if (focusable.length === 0) {
        event.preventDefault();
        activeModal.focus();
        return;
    }

    const first = focusable[0];
    const last = focusable[focusable.length - 1];
    if (!activeModal.contains(document.activeElement)) {
        event.preventDefault();
        (event.shiftKey ? last : first).focus();
    } else if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
    }
}

document.addEventListener('keydown', trapModalFocus);

function setNavigationOpen(isOpen, returnFocus = false) {
    if (!header || !navToggle || !navPanel) return;

    const shouldOpen = mobileNavigation.matches && isOpen;
    header.classList.toggle('nav-open', shouldOpen);
    navToggle.setAttribute('aria-expanded', shouldOpen ? 'true' : 'false');

    if (mobileNavigation.matches) {
        navPanel.setAttribute('aria-hidden', shouldOpen ? 'false' : 'true');
        navPanel.inert = !shouldOpen;
    } else {
        navPanel.removeAttribute('aria-hidden');
        navPanel.inert = false;
    }

    if (returnFocus) navToggle.focus();
}

if (navToggle && navPanel) {
    navToggle.addEventListener('click', () => {
        setNavigationOpen(navToggle.getAttribute('aria-expanded') !== 'true');
    });

    navPanel.querySelectorAll('a').forEach(link => {
        link.addEventListener('click', () => setNavigationOpen(false));
    });

    mobileNavigation.addEventListener('change', () => setNavigationOpen(false));
    setNavigationOpen(false);
}

document.addEventListener('keydown', event => {
    if (event.key === 'Escape' && header?.classList.contains('nav-open')) {
        setNavigationOpen(false, true);
    }
});

// Smooth scrolling for in-page navigation. Tebi handles its own reservation links.
document.querySelectorAll('a[href^="#"]').forEach(anchor => {
    anchor.addEventListener('click', function (event) {
        const href = this.getAttribute('href');
        if (href === '#menu' || href === '#tebi-reservations') return;

        const target = document.querySelector(href);
        if (!target) return;

        event.preventDefault();
        target.scrollIntoView({
            behavior: reducedMotion.matches ? 'auto' : 'smooth',
            block: 'start'
        });

        if (this.classList.contains('skip-link')) {
            target.focus({ preventScroll: true });
        }

        if (window.history?.replaceState) {
            window.history.replaceState(null, '', href);
        }
    });
});

const navLinks = Array.from(document.querySelectorAll('.nav-menu a[href^="#"]'));
const observedSections = navLinks
    .map(link => document.querySelector(link.getAttribute('href')))
    .filter(Boolean);

function updatePageChrome() {
    header?.classList.toggle('is-scrolled', window.scrollY > 32);

    let current = 'home';
    observedSections.forEach(section => {
        if (window.scrollY >= section.offsetTop - 180) current = section.id;
    });

    navLinks.forEach(link => {
        const isActive = link.getAttribute('href') === `#${current}`;
        link.classList.toggle('active', isActive);
        if (isActive) {
            link.setAttribute('aria-current', 'page');
        } else {
            link.removeAttribute('aria-current');
        }
    });
}

window.addEventListener('scroll', updatePageChrome, { passive: true });
updatePageChrome();

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
        const response = await fetch('data/content.json?v=20260804', { cache: 'no-cache' });
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
const lightboxClose = lightbox?.querySelector('.lightbox-close');
const menuDocPages = document.getElementById('menu-doc-pages');
const menuDocTabs = Array.from(document.querySelectorAll('.menu-doc-tab'));
let menuTrigger = null;

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
        tab.tabIndex = isActive ? 0 : -1;
    });
}

function openLightbox(e) {
    e.preventDefault();
    menuTrigger = mobileNavigation.matches && e.currentTarget === menuNavLink
        ? navToggle
        : e.currentTarget;
    const activeTab = menuDocTabs.find(tab => tab.classList.contains('active'));
    if (activeTab) {
        renderMenuDocument(activeTab.dataset.doc);
    }
    lightbox.classList.add('active');
    activateModal(lightbox, lightboxClose);
}

function closeLightbox() {
    lightbox.classList.remove('active');
    deactivateModal(lightbox, menuTrigger);
    menuTrigger = null;
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
    tab.addEventListener('keydown', event => {
        const currentIndex = menuDocTabs.indexOf(tab);
        let nextIndex = null;

        if (event.key === 'ArrowRight') nextIndex = (currentIndex + 1) % menuDocTabs.length;
        if (event.key === 'ArrowLeft') nextIndex = (currentIndex - 1 + menuDocTabs.length) % menuDocTabs.length;
        if (event.key === 'Home') nextIndex = 0;
        if (event.key === 'End') nextIndex = menuDocTabs.length - 1;
        if (nextIndex === null) return;

        event.preventDefault();
        const nextTab = menuDocTabs[nextIndex];
        renderMenuDocument(nextTab.dataset.doc);
        nextTab.focus();
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
            alts: ['Dezerty', 'Prkénko s občerstvením', 'Limonáda', 'Dezert s malinou']
        },
        'kava-card': {
            photos: [
                'images/kava/kava-malina-limo.jpg',
                'images/kava/kava-3-druhy.jpg',
                'images/kava/kava-costarica.webp',
                'images/kava/kava-guatemala.webp',
                'images/kava/kava-mexiko.webp'
            ],
            alts: ['Káva, limonáda a dezert', '3 druhy kávy', 'Costa Rica', 'Guatemala', 'Mexiko']
        },
        'vina-card': {
            photos: [
                'images/vina/vino-luxus.png',
                'images/vina/vine.jpg',
                'images/vina/vino-korky.png',
                'images/vina/ryzlink-wachau.png',
                'images/vina/pecorino-bile.png',
                'images/vina/italske-bile-vino.png',
                'images/vina/veltlin-prechtl.png',
                'images/vina/rocca-montemassi.png',
                'images/vina/chianti-riserva.png',
                'images/vina/nero-davola.png',
                'images/vina/primitivo-manduria.png',
                'images/vina/rosso-montalcino.png',
                'images/vina/barolo-le-terre.png',
                'images/vina/amarone-valpolicella.png',
                'images/vina/prosecco-spumante.png',
                'images/vina/sekt-oranzovy.png',
                'images/vina/sekt-bily.png'
            ],
            alts: [
                'Vybraná vína',
                'Nalévání vína',
                'Víno a korky',
                'Ryzlink z Wachau',
                'Bílé Pecorino',
                'Italské bílé víno',
                'Veltlín Prechtl',
                'Rocca di Montemassi',
                'Chianti Riserva',
                'Nero d\'Avola',
                'Primitivo di Manduria',
                'Rosso di Montalcino',
                'Barolo Le Terre',
                'Amarone della Valpolicella',
                'Prosecco Spumante',
                'Oranžový sekt',
                'Bílý sekt'
            ]
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
    let galleryTrigger = null;
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

        const wasOpen = lightbox.classList.contains('active');
        if (!wasOpen) galleryTrigger = card;

        activeGallery = { ...gallery, cardId };
        currentIndex = index;
        title.textContent = card.querySelector('.card-title')?.textContent?.trim() || '';
        slideImg.src = activeGallery.photos[currentIndex];
        slideImg.alt = activeGallery.alts[currentIndex] || title.textContent;
        renderDots();
        updateDots();
        lightbox.classList.add('active');
        if (!wasOpen) {
            activateModal(lightbox, lightbox.querySelector('.lightbox-close'));
        }
    }

    function closeGallery() {
        lightbox.classList.remove('active');
        deactivateModal(lightbox, galleryTrigger);
        galleryTrigger = null;
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
    loadContent();
});
