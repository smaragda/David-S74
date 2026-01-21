// Hamburger menu toggle
const menuToggle = document.getElementById('menu-toggle');
const mobileMenu = document.getElementById('mobile-menu');
const menu = document.getElementById('menu');

if (menuToggle) {
    menuToggle.addEventListener('click', () => {
        mobileMenu.classList.toggle('hidden');
    });
}

// Smooth scroll pro anchor odkazy
document.querySelectorAll('a[href^="#"]').forEach(anchor => {
    anchor.addEventListener('click', function (e) {
        e.preventDefault();
        const target = document.querySelector(this.getAttribute('href'));
        if (target) {
            target.scrollIntoView({
                behavior: 'smooth',
                block: 'start'
            });
            // Zavřít mobilní menu po kliknutí
            if (mobileMenu && !mobileMenu.classList.contains('hidden')) {
                mobileMenu.classList.add('hidden');
            }
        }
    });
});

// Validace formuláře (základní)
const contactForm = document.querySelector('#kontakt form');
if (contactForm) {
    contactForm.addEventListener('submit', function(e) {
        e.preventDefault();
        
        const name = this.querySelector('input[type="text"]').value.trim();
        const email = this.querySelector('input[type="email"]').value.trim();
        const message = this.querySelector('textarea').value.trim();
        
        if (name && email && message) {
            alert('Děkujeme za vaši zprávu! Brzy se vám ozveme.');
            this.reset();
        } else {
            alert('Prosím vyplňte všechna pole.');
        }
    });
}
