/* ============================================================
   S74 wine&café — Production JavaScript
   Author: OpenCode Agent
   Version: 1.0
   Vanilla JS, no dependencies.
   ============================================================ */

(function () {
  'use strict';

  // --- helpers ------------------------------------------------
  const $  = (sel, scope = document) => scope.querySelector(sel);
  const $$ = (sel, scope = document) => Array.from(scope.querySelectorAll(sel));

  // --- 1. Header scroll background ---------------------------
  const header = $('#header');
  function updateHeader() {
    if (!header) return;
    header.classList.toggle('is-scrolled', window.scrollY > 12);
  }
  window.addEventListener('scroll', updateHeader, { passive: true });
  updateHeader();

  // --- 2. Mobile navigation ----------------------------------
  const menuToggle = $('#menu-toggle');
  const menuList   = $('#menu-list');

  function closeMenu() {
    if (!menuToggle || !menuList) return;
    menuToggle.setAttribute('aria-expanded', 'false');
    menuList.classList.remove('is-open');
    document.body.style.overflow = '';
  }
  function openMenu() {
    if (!menuToggle || !menuList) return;
    menuToggle.setAttribute('aria-expanded', 'true');
    menuList.classList.add('is-open');
    document.body.style.overflow = 'hidden';
  }

  if (menuToggle && menuList) {
    menuToggle.addEventListener('click', () => {
      const expanded = menuToggle.getAttribute('aria-expanded') === 'true';
      expanded ? closeMenu() : openMenu();
    });

    menuList.querySelectorAll('a[href^="#"]').forEach(link => {
      link.addEventListener('click', closeMenu);
    });

    document.addEventListener('keydown', (e) => {
      if (e.key === 'Escape' && menuList.classList.contains('is-open')) {
        closeMenu();
        menuToggle.focus();
      }
    });
  }

  // --- 3. Smooth scroll with offset --------------------------
  const HEADER_OFFSET = 76;
  document.addEventListener('click', (e) => {
    const link = e.target.closest('a[href^="#"]');
    if (!link) return;
    const id = link.getAttribute('href');
    if (!id || id === '#') return;
    const target = $(id);
    if (!target) return;
    e.preventDefault();
    const y = target.getBoundingClientRect().top + window.scrollY - HEADER_OFFSET;
    window.scrollTo({ top: y, behavior: 'smooth' });
  });

  // --- 4. Reveal on scroll (IntersectionObserver) ------------
  const revealObserver = new IntersectionObserver((entries) => {
    entries.forEach(entry => {
      if (entry.isIntersecting) {
        entry.target.classList.add('is-visible');
        revealObserver.unobserve(entry.target);
      }
    });
  }, { root: null, rootMargin: '0px 0px -40px 0px', threshold: 0.1 });

  $$('.reveal').forEach(el => revealObserver.observe(el));

  // --- 5. Lightbox -------------------------------------------
  const lightbox      = $('#lightbox');
  const lightboxImg   = $('#lightbox-img');
  const lightboxCap   = $('#lightbox-caption');
  const lbClose       = $('.lightbox-close');
  const lbPrev        = $('.lightbox-prev');
  const lbNext        = $('.lightbox-next');
  const galleryItems  = $$('.gallery-item');
  let lbIndex = 0;
  let lbLastFocused = null;

  function openLightbox(index) {
    if (!lightbox || !lightboxImg || !galleryItems.length) return;
    lbIndex = index;
    const img = galleryItems[index].querySelector('img');
    if (!img) return;

    lightboxImg.src = img.src;
    lightboxImg.alt = img.alt || '';
    if (lightboxCap) lightboxCap.textContent = img.alt || '';

    lightbox.hidden = false;
    requestAnimationFrame(() => lightbox.classList.add('is-open'));
    document.body.classList.add('lightbox-open');
    lbLastFocused = document.activeElement;
    if (lbClose) lbClose.focus();
  }

  function closeLightbox() {
    if (!lightbox) return;
    lightbox.classList.remove('is-open');
    document.body.classList.remove('lightbox-open');

    const onEnd = (e) => {
      if (e.propertyName === 'opacity') {
        lightbox.hidden = true;
        lightbox.removeEventListener('transitionend', onEnd);
      }
    };
    lightbox.addEventListener('transitionend', onEnd);
    setTimeout(() => { lightbox.hidden = true; }, 450);

    if (lbLastFocused) lbLastFocused.focus();
  }

  function showPrev() { lbIndex = (lbIndex - 1 + galleryItems.length) % galleryItems.length; openLightbox(lbIndex); }
  function showNext() { lbIndex = (lbIndex + 1) % galleryItems.length; openLightbox(lbIndex); }

  galleryItems.forEach((item, i) => {
    item.setAttribute('tabindex', '0');
    item.setAttribute('role', 'button');
    item.style.cursor = 'zoom-in';
    item.addEventListener('click', () => openLightbox(i));
    item.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); openLightbox(i); }
    });
  });

  if (lbClose) lbClose.addEventListener('click', closeLightbox);
  if (lbPrev)  lbPrev.addEventListener('click', showPrev);
  if (lbNext)  lbNext.addEventListener('click', showNext);

  if (lightbox) {
    lightbox.addEventListener('keydown', (e) => {
      if (e.key === 'Escape') { closeLightbox(); }
      else if (e.key === 'ArrowLeft') { showPrev(); }
      else if (e.key === 'ArrowRight') { showNext(); }
    });
    lightbox.addEventListener('click', (e) => {
      if (e.target === lightbox) closeLightbox();
    });
  }

  // Focus trap inside lightbox
  if (lightbox) {
    const focusables = () => $$('button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])', lightbox);
    lightbox.addEventListener('keydown', (e) => {
      if (e.key !== 'Tab') return;
      const els = focusables();
      if (!els.length) return;
      const first = els[0];
      const last  = els[els.length - 1];
      if (e.shiftKey && document.activeElement === first) {
        e.preventDefault(); last.focus();
      } else if (!e.shiftKey && document.activeElement === last) {
        e.preventDefault(); first.focus();
      }
    });
  }

  // --- 6. Footer year ----------------------------------------
  const yearEl = $('#year');
  if (yearEl) yearEl.textContent = String(new Date().getFullYear());
})();
